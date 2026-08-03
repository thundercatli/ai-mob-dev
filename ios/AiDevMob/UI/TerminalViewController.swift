import UIKit
import SwiftTerm

/// First repeat delay for held extra keys — matches Android's `KEY_REPEAT_DELAY_MS`.
private let keyRepeatDelay: TimeInterval = 0.4
/// Interval between repeats while a key is held — matches Android's `KEY_REPEAT_INTERVAL_MS`.
private let keyRepeatInterval: TimeInterval = 0.06

/// iOS port of Android's `TerminalActivity` terminal screen.
///
/// Renders the remote shell through SwiftTerm's `TerminalView` (the same injected-I/O
/// architecture as Android's vendored Termux) with a horizontally scrolling extra-keys
/// row that mirrors Android's `buildExtraKeysRow`. The SSH layer wires itself up through
/// the public closures; this controller is UI-only and never touches sockets.
final class TerminalViewController: UIViewController, TerminalViewDelegate, UIGestureRecognizerDelegate {

    // MARK: - Wired up by the SSH layer

    /// The connection profile this terminal is attached to.
    let config: ConnectionConfig

    /// Receives keystrokes — extra-row taps and soft-keyboard input — destined for the
    /// remote shell. The SSH connector sets this to write into its channel.
    var onSend: ([UInt8]) -> Void = { _ in }

    /// Called when the terminal grid size changes so the caller can send a PTY
    /// window-change (Android: `SshTerminalConnector`'s window-change hook).
    var onResize: ((_ cols: Int, _ rows: Int) -> Void)?

    /// Called when the user taps the status to force a reconnect.
    var onReconnect: () -> Void = {}

    /// Opens the SFTP browser for this terminal's profile. The coordinator reuses this terminal's
    /// SSH transport so the PTY stays alive while the file sheet is visible.
    var onBrowseFiles: () -> Void = {}

    /// Called when this screen is going away so the caller can tear down the SSH channel.
    /// Never blocks the main thread here — the caller handles that asynchronously, exactly
    /// like Android's `disconnectInBackground()`.
    var onDisconnect: () -> Void = {}

    /// Called when the user taps the back button so the coordinator can pop this screen.
    var onClose: () -> Void = {}

    // MARK: - Connection status (drives the top-bar status pill)

    enum Status: Equatable {
        case connecting
        case tunnelStarting
        case connected
        case reconnecting
        case disconnected
        case failed(String)

        var label: String {
            switch self {
            case .connecting:     return "连接中"
            case .tunnelStarting: return "隧道启动中"
            case .connected:      return "已连接"
            case .reconnecting:   return "重连中"
            case .disconnected:   return "已断开"
            case .failed(let m):  return m
            }
        }

        /// Color of the status dot.
        var dotColor: UIColor {
            switch self {
            case .connected:      return UIColor(red: 0.30, green: 0.85, blue: 0.40, alpha: 1)
            case .connecting,
                 .tunnelStarting,
                 .reconnecting:    return UIColor(red: 0.95, green: 0.69, blue: 0.20, alpha: 1)
            case .disconnected,
                 .failed:          return UIColor(red: 0.92, green: 0.38, blue: 0.38, alpha: 1)
            }
        }

        /// Whether the whole top bar should pulse to draw attention (only transient/failed states).
        var highlightsBar: Bool {
            switch self {
            case .connected: return false
            default:         return true
            }
        }
    }

    // MARK: - Views

    private let topBar = UIView()
    private let backButton = UIButton(type: .system)
    private let filesButton = UIButton(type: .system)
    private let moreButton = UIButton(type: .system)
    private let titleLabel = UILabel()
    private let statusDot = UIView()
    private let statusText = UILabel()
    private let terminalView = PasteAwareTerminalView(frame: .zero)
    /// The extra-keys row, pinned below the terminal (so it reads as part of the terminal). It
    /// scrolls horizontally — there are more keys than fit on a phone. Both terminalView and this
    /// bar sit above `keyboardLayoutGuide.topAnchor`, so the whole cluster lifts above the
    /// keyboard and the terminal resizes to the visible area. No inputAccessoryView: those are
    /// unreliable for scrollable content (UIKit owns their frame), and this approach gives the
    /// same "keys stuck to the terminal" feel with a working scroll.
    private let extraKeysBar = ExtraKeysAccessoryBar()

    /// extraKeysBar's bottom constraint; its constant is lifted by the keyboard height so the
    /// bar (and the terminal above it) rise above the keyboard.
    private var extraKeysBottomConstraint: NSLayoutConstraint?
    /// extraKeysBar's height constraint; set to barHeight + the bottom safe-area inset once known
    /// so the keys sit above the home indicator while the black fill extends under it.
    private var extraKeysHeightConstraint: NSLayoutConstraint?

    /// Ctrl modifier toggled from the extra-keys row. Applied to soft-keyboard input in
    /// `send(source:data:)`, mirroring Android's `AppTerminalViewClient.ctrlDown`.
    private var ctrlDown = false

    /// Current status; updating it refreshes the top-bar pill. Main-thread only.
    private var status: Status = .connecting {
        didSet { guard oldValue != status else { return }; applyStatus() }
    }

    // MARK: - Init

    init(config: ConnectionConfig) {
        self.config = config
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    // MARK: - View lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        applySettings()
        setupTopBar()
        setupExtraKeysBar()
        setupTerminalView()
        installSwipeGestures()

        // Kill SwiftTerm's built-in TerminalAccessory (the default inputAccessoryView). We
        // render our own key row as a normal subview below; leaving the default would show two.
        terminalView.inputAccessoryView = nil

        registerKeyboardNotifications()

        status = .connecting
    }

    /// Applies terminal-relevant settings from SettingsStore: font size (via SwiftTerm's writable
    /// `font` property), keep-screen-on (idle timer), and swipe-to-switch-windows (gesture enabled).
    /// Mirrors Android's `TerminalActivity.onCreate` reading `AppSettings`.
    private func applySettings() {
        let fontSize = CGFloat(SettingsStore.shared.terminalFontSize)
        // SwiftTerm's TerminalView.font setter rebuilds the renderer at the new size and
        // recomputes rows/cols — same effect as Android's TerminalView.setTextSize.
        terminalView.font = UIFont(name: "Menlo", size: fontSize) ?? .monospacedSystemFont(ofSize: fontSize, weight: .regular)

        if SettingsStore.shared.keepScreenOn {
            UIApplication.shared.isIdleTimerDisabled = true
        }
    }

    /// Left/right swipe → tmux next/prev window, gated by the swipeSwitchesWindows setting and
    /// only when a tmux session is configured. Mirrors Android's dispatchTouchEvent swipe handler.
    private func installSwipeGestures() {
        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(swipeNextWindow))
        swipeLeft.direction = .left
        swipeLeft.numberOfTouchesRequired = 1
        terminalView.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(swipePrevWindow))
        swipeRight.direction = .right
        swipeRight.numberOfTouchesRequired = 1
        terminalView.addGestureRecognizer(swipeRight)
    }

    @objc private func swipeNextWindow() {
        guard SettingsStore.shared.swipeSwitchesWindows,
              config.tmuxSession.isNotBlank else { return }
        sendTmuxKey("n")
    }

    @objc private func swipePrevWindow() {
        guard SettingsStore.shared.swipeSwitchesWindows,
              config.tmuxSession.isNotBlank else { return }
        sendTmuxKey("p")
    }

    /// Sends a tmux prefix + key (e.g. prefix + "n" = next window). Reads the configured prefix
    /// from SettingsStore, matching Android's `sendTmuxKey`.
    private func sendTmuxKey(_ key: String) {
        let prefix = SettingsStore.shared.tmuxPrefix
        let control = UInt8(prefix.asciiValue! - Character("a").asciiValue! + 1)
        sendBytes([control])
        sendBytes(Array(key.utf8))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Like Android's `requestFocus()`: opens the soft keyboard right away.
        terminalView.becomeFirstResponder()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Always re-enable the idle timer when leaving; applySettings may have disabled it.
        UIApplication.shared.isIdleTimerDisabled = false
        onDisconnect()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Once the safe-area insets are known, grow the bar so its black fill reaches under the
        // home indicator while the 36pt key strip stays at the top of the bar.
        let inset = view.safeAreaInsets.bottom
        extraKeysHeightConstraint?.constant = ExtraKeysAccessoryBar.barHeight + inset
    }

    // MARK: - Remote output

    /// Feeds remote stdout bytes into the emulator. The SSH reader runs on a background
    /// thread, but `Terminal` isn't thread-safe, so the feed is marshalled to the main
    /// thread (the main queue is serial, so chunk order is preserved).
    func feed(_ bytes: [UInt8]) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            // Must call TerminalView.feed (not Terminal.feed): the view-level feed wraps the
            // data in feedPrepare()/feedFinish(), which resume the CADisplayLink render loop
            // and queue a redraw. Calling the underlying Terminal.feed directly updates only
            // the in-memory buffer — the display link stays suspended (its initial state) and
            // nothing ever renders.
            self.terminalView.feed(byteArray: ArraySlice(bytes))
            // First bytes mean the shell is live — flip to connected (idempotent).
            if self.status != .connected { self.status = .connected }
        }
    }

    // MARK: - Status (public, called by the coordinator)

    func setStatus(_ status: Status) {
        DispatchQueue.main.async { [weak self] in self?.status = status }
    }

    /// The terminal's current column/row count, used for the initial PTY size so the shell/tmux
    /// starts at the right dimensions instead of a hardcoded default.
    func currentTerminalSize() -> (cols: Int, rows: Int) {
        let t = terminalView.getTerminal()
        let cols = max(t.cols, 1)
        let rows = max(t.rows, 1)
        return (cols, rows)
    }

    // MARK: - Layout: top bar

    /// Compact top bar: back chevron, connection title, and a status pill (dot + text) on
    /// the right. The status lives here instead of a separate banner, so it never overlaps
    /// content and the bar is the only thing at the top of the screen.
    private func setupTopBar() {
        topBar.backgroundColor = .black
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        let symbolConfig = UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        backButton.setImage(UIImage(systemName: "chevron.left", withConfiguration: symbolConfig), for: .normal)
        backButton.tintColor = .white
        backButton.translatesAutoresizingMaskIntoConstraints = false
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        topBar.addSubview(backButton)

        titleLabel.text = config.displayName
        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(titleLabel)

        filesButton.setImage(UIImage(systemName: "folder", withConfiguration: symbolConfig), for: .normal)
        filesButton.tintColor = .white
        filesButton.translatesAutoresizingMaskIntoConstraints = false
        filesButton.accessibilityLabel = "浏览文件"
        filesButton.addTarget(self, action: #selector(filesTapped), for: .touchUpInside)
        topBar.addSubview(filesButton)

        moreButton.setImage(UIImage(systemName: "ellipsis.circle", withConfiguration: symbolConfig), for: .normal)
        moreButton.tintColor = .white
        moreButton.translatesAutoresizingMaskIntoConstraints = false
        moreButton.accessibilityLabel = "更多操作"
        moreButton.showsMenuAsPrimaryAction = true
        moreButton.menu = makeActionsMenu()
        topBar.addSubview(moreButton)

        statusDot.layer.cornerRadius = 4
        statusDot.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(statusDot)

        statusText.font = .systemFont(ofSize: 12)
        statusText.textColor = UIColor(white: 0.85, alpha: 1)
        statusText.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(statusText)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 36),

            backButton.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 2),
            backButton.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            backButton.widthAnchor.constraint(equalToConstant: 36),

            titleLabel.leadingAnchor.constraint(equalTo: backButton.trailingAnchor, constant: 0),
            titleLabel.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),

            moreButton.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -2),
            moreButton.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            moreButton.widthAnchor.constraint(equalToConstant: 36),
            moreButton.heightAnchor.constraint(equalToConstant: 36),

            filesButton.trailingAnchor.constraint(equalTo: moreButton.leadingAnchor),
            filesButton.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            filesButton.widthAnchor.constraint(equalToConstant: 36),
            filesButton.heightAnchor.constraint(equalToConstant: 36),

            statusText.trailingAnchor.constraint(equalTo: filesButton.leadingAnchor, constant: -6),
            statusText.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),

            statusDot.trailingAnchor.constraint(equalTo: statusText.leadingAnchor, constant: -5),
            statusDot.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            statusDot.widthAnchor.constraint(equalToConstant: 8),
            statusDot.heightAnchor.constraint(equalToConstant: 8),

            // Title must not run into the status pill.
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: statusDot.leadingAnchor, constant: -6),
        ])

        // Tap the status pill to force a reconnect (mirrors Android's tappable status banner).
        statusDot.isUserInteractionEnabled = true
        statusText.isUserInteractionEnabled = true
        statusDot.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(statusTapped)))
        statusText.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(statusTapped)))
    }

    @objc private func backTapped() {
        // Dismiss the keyboard first so the pop animation isn't janky.
        terminalView.resignFirstResponder()
        onClose()
    }

    @objc private func statusTapped() {
        onReconnect()
    }

    @objc private func filesTapped() {
        _ = terminalView.resignFirstResponder()
        onBrowseFiles()
    }

    private func makeActionsMenu() -> UIMenu {
        let keyboard = UIAction(title: "显示键盘", image: UIImage(systemName: "keyboard")) { [weak self] _ in
            _ = self?.terminalView.becomeFirstResponder()
        }
        let files = UIAction(title: "浏览文件", image: UIImage(systemName: "folder")) { [weak self] _ in
            self?.filesTapped()
        }

        var children: [UIMenuElement] = [keyboard, files]
        if config.tmuxSession.isNotBlank {
            let tmuxActions: [UIMenuElement] = [
                tmuxAction("新建窗口", image: "plus.rectangle", key: "c"),
                tmuxAction("上一个窗口", image: "arrow.left.to.line", key: "p"),
                tmuxAction("下一个窗口", image: "arrow.right.to.line", key: "n"),
                tmuxAction("窗口列表", image: "list.bullet.rectangle", key: "w"),
                tmuxAction("重命名窗口", image: "pencil", key: ","),
            ]
            children.append(UIMenu(title: "tmux", options: .displayInline, children: tmuxActions))
        }

        children.append(UIMenu(title: "", options: .displayInline, children: [
            UIAction(title: "重新连接", image: UIImage(systemName: "arrow.clockwise")) { [weak self] _ in
                self?.onReconnect()
            },
            UIAction(
                title: "断开并返回",
                image: UIImage(systemName: "xmark.circle"),
                attributes: .destructive
            ) { [weak self] _ in
                guard let self else { return }
                _ = self.terminalView.resignFirstResponder()
                self.onClose()
            },
        ]))
        return UIMenu(children: children)
    }

    private func tmuxAction(_ title: String, image: String, key: String) -> UIAction {
        UIAction(title: title, image: UIImage(systemName: image)) { [weak self] _ in
            self?.sendTmuxKey(key)
        }
    }

    private func applyStatus() {
        statusText.text = status.label
        statusDot.backgroundColor = status.dotColor
        // Only the status dot + text change colour; the bar stays black so the terminal reads as
        // one continuous surface. A subtle dark-amber tint on transient/failed states only.
        topBar.backgroundColor = status.highlightsBar
            ? UIColor(red: 0.30, green: 0.17, blue: 0.0, alpha: 1)
            : .black
    }

    // MARK: - Layout: extra keys bar

    /// Pins the extra-keys bar below the terminal. The bar's bottom constraint constant is
    /// adjusted by the keyboard handler (manual, via keyboardWillChangeFrame) so the whole
    /// cluster lifts above the keyboard. keyboardLayoutGuide was tried but did not move the bar
    /// on this target; the manual path is reliable.
    private func setupExtraKeysBar() {
        extraKeysBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(extraKeysBar)

        // Bottom pins to view.bottom (NOT safeArea) so the bar's black background extends all the
        // way to the screen edge, under the home indicator — no dead safe-area strip below the
        // keys. The bar's scrollable content (buttons) sits in the top 36pt; the area below is
        // black fill that the home indicator floats over. Height = 36 + safe-area-bottom.
        let bottom = extraKeysBar.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        extraKeysBottomConstraint = bottom
        let height = extraKeysBar.heightAnchor.constraint(equalToConstant: ExtraKeysAccessoryBar.barHeight)
        extraKeysHeightConstraint = height

        NSLayoutConstraint.activate([
            extraKeysBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            extraKeysBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottom,
            height,
        ])

        extraKeysBar.configure(
            config: config,
            send: { [weak self] bytes in self?.sendBytes(bytes) },
            setCtrl: { [weak self] on in self?.ctrlDown = on },
            cursor: { [weak self] finalByte in self?.sendCursorKey(finalByte: finalByte) },
            tmux: { [weak self] key in
                guard let self else { return }
                self.sendTmuxKey(key)
            },
            hideKeyboard: { [weak self] in
                // Dismiss the soft keyboard so the terminal fills more of the screen; tapping the
                // terminal area brings it back (it's first-responder-eligible).
                self?.terminalView.resignFirstResponder()
            }
        )
    }

    // MARK: - Layout: terminal

    private func setupTerminalView() {
        terminalView.terminalDelegate = self
        terminalView.backgroundColor = .black
        // Keep mouse reporting ON so TUI programs (opencode/vim/tmux) receive wheel events and
        // can scroll their own content / enter copy mode. But SwiftTerm's built-in pan handler
        // forwards vertical pans as mouse-drag (button-motion) events, which TUIs interpret as
        // text selection ("copied to clipboard" in opencode). We add our own pan gesture below
        // that intercepts vertical scrolls and sends proper wheel events instead — mirroring
        // Android Termux's `doScroll`, which only ever sends MOUSE_WHEELUP/WHEELDOWN.
        terminalView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(terminalView)

        installWheelScrollGesture()

        NSLayoutConstraint.activate([
            terminalView.topAnchor.constraint(equalTo: topBar.bottomAnchor),
            terminalView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            terminalView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            // Terminal sits directly above the extra-keys bar.
            terminalView.bottomAnchor.constraint(equalTo: extraKeysBar.topAnchor),
        ])
    }

    // MARK: - Wheel-scroll gesture

    /// Accumulated vertical pan distance not yet turned into a row, like Termux's mScrollRemainder.
    private var wheelScrollRemainder: CGFloat = 0

    /// Installs a vertical-pan gesture that, when the foreground TUI has enabled mouse tracking,
    /// sends proper mouse wheel events (button 64=up, 65=down) instead of letting SwiftTerm
    /// forward the pan as a button-drag (which TUIs read as text selection). This mirrors
    /// Android Termux's `doScroll`, the reason scrolling works on Android inside vim/tmux/less.
    /// When mouse tracking is off (plain shell) the gesture does nothing and SwiftTerm scrolls
    /// its own scrollback.
    private func installWheelScrollGesture() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(wheelScroll(_:)))
        pan.delegate = self
        // A tiny delay so taps still feel instant; this mainly needs to beat the scroll view's
        // own pan when we want to consume the gesture.
        pan.maximumNumberOfTouches = 1
        terminalView.addGestureRecognizer(pan)
    }

    @objc private func wheelScroll(_ gr: UIPanGestureRecognizer) {
        let term = terminalView.getTerminal()
        // Only hijack the gesture when the TUI is tracking the mouse; otherwise let SwiftTerm
        // handle the scroll itself (plain-shell scrollback).
        guard term.mouseMode != .off, terminalView.allowMouseReporting else { return }

        let dy = gr.translation(in: terminalView).y
        gr.setTranslation(.zero, in: terminalView)

        // Cell height from the view frame and terminal rows.
        let cellHeight = max(terminalView.bounds.height / CGFloat(max(term.rows, 1)), 1)
        wheelScrollRemainder += dy
        let rows = Int(wheelScrollRemainder / cellHeight)
        wheelScrollRemainder -= CGFloat(rows) * cellHeight
        guard rows != 0 else { return }

        // SwiftTerm encodeButton: button 4 -> 64 (wheel up), 5 -> 65 (wheel down).
        // Negative dy (finger up = scroll toward history) → wheel up.
        let button = rows < 0 ? 4 : 5
        let flags = term.encodeButton(button: button, release: false, shift: false, meta: false, control: false)
        // Approximate grid cell at the touch point; the column matters less than the row for
        // scrolling, and most TUIs only act on the wheel direction. Clamp to terminal bounds.
        let loc = gr.location(in: terminalView)
        let col = min(max(Int(loc.x / (terminalView.bounds.width / CGFloat(max(term.cols, 1)))), 0), term.cols - 1)
        let row = min(max(Int(loc.y / cellHeight), 0), term.rows - 1)
        for _ in 0..<abs(rows) {
            term.sendEvent(buttonFlags: flags, x: col, y: row)
        }
    }

    // MARK: - UIGestureRecognizerDelegate

    /// Our wheel gesture must NOT run simultaneously with the scroll view's built-in pan —
    /// otherwise both fire: we send wheel events AND SwiftTerm sends drag (text-select) events,
    /// which is why scrolling sometimes became "copied to clipboard". When mouse tracking is on
    /// we claim the gesture exclusively.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        false
    }

    /// Let the scroll view's own pan fail when we're handling the gesture (mouse tracking on),
    /// so only our wheel events reach the TUI. The scroll view's pan runs for plain-shell scrollback.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // Only require-fail the scroll view's pan (UIPanGestureRecognizer on a UIScrollView),
        // and only when we're actually going to handle the scroll (mouse tracking on).
        guard otherGestureRecognizer is UIPanGestureRecognizer,
              otherGestureRecognizer.view === terminalView else { return false }
        let term = terminalView.getTerminal()
        return term.mouseMode != .off && terminalView.allowMouseReporting
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        let term = terminalView.getTerminal()
        // Only take over when a TUI is mouse-tracking; otherwise let the scroll view pan.
        return term.mouseMode != .off && terminalView.allowMouseReporting
    }

    // MARK: - Keyboard avoidance

    /// Lifts the extra-keys bar (and thus the terminal above it) when the keyboard appears, so
    /// the terminal shrinks to the visible area and SwiftTerm resizes the PTY. Uses
    /// keyboardWillChangeFrame with a coordinate conversion into the window (not the view, which
    /// can be misleading when the view itself is offset).
    private func registerKeyboardNotifications() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(keyboardWillHide(_:)),
            name: UIResponder.keyboardWillHideNotification, object: nil
        )
    }

    @objc private func keyboardWillChangeFrame(_ note: Notification) {
        guard let frameEnd = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        let curveRaw = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? Int) ?? UIView.AnimationCurve.easeInOut.rawValue

        // The keyboard frame is in window coordinates. Convert into our view's coordinate
        // space and measure how much it overlaps the bottom of our view — that's the lift.
        let kbInView = view.convert(frameEnd, from: nil)
        // Only count the part of the keyboard that actually overlaps our view.
        let viewBottom = view.bounds.height
        let overlap = max(0, viewBottom - kbInView.origin.y)
        adjustBottom(to: overlap, duration: duration, curve: curveRaw)
    }

    @objc private func keyboardWillHide(_ note: Notification) {
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        let curve = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? Int) ?? UIView.AnimationCurve.easeInOut.rawValue
        adjustBottom(to: 0, duration: duration, curve: curve)
    }

    private func adjustBottom(to height: CGFloat, duration: Double, curve: Int) {
        guard let constraint = extraKeysBottomConstraint else { return }
        constraint.constant = -height
        // When the keyboard is up it covers the home indicator, so the bar no longer needs the
        // bottom safe-area fill — collapse it to just the 36pt key strip, eliminating the empty
        // black band that would otherwise sit between the keys and the keyboard's top.
        extraKeysHeightConstraint?.constant = height > 0
            ? ExtraKeysAccessoryBar.barHeight
            : ExtraKeysAccessoryBar.barHeight + view.safeAreaInsets.bottom
        UIView.animate(withDuration: duration, delay: 0,
                       options: UIView.AnimationOptions(rawValue: UInt(curve << 16))) { [weak self] in
            self?.view.layoutIfNeeded()
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Sending keys

    /// Sends raw bytes to the remote shell, bypassing the CTRL modifier — extra-row keys
    /// mirror Android's `session.write`, which never consults `readControlKey()`.
    private func sendBytes(_ bytes: [UInt8]) {
        onSend(bytes)
    }

    /// Builds an arrow/Home/End byte sequence respecting the terminal's application-cursor
    /// mode (DECCKM): full-screen TUIs (vim, tmux, htop) switch to it, and the encoding flips
    /// from `ESC [ x` to `ESC O x`. SwiftTerm exposes the mode on its Terminal; the accessory
    /// bar calls this so its arrow keys work inside TUIs the same as the soft keyboard.
    private func sendCursorKey(finalByte: UInt8) {
        let leader: UInt8 = terminalView.getTerminal().applicationCursor ? 0x4F : 0x5B // ESC O / ESC [
        sendBytes([0x1B, leader, finalByte])
    }

    /// Ctrl transform for soft-keyboard input while the CTRL toggle is on, mirroring
    /// Termux's `inputCodePoint`: a-z/A-Z map to their position in the alphabet (1...26),
    /// everything else is ANDed with 0x1f.
    private func applyControl(_ byte: UInt8) -> UInt8 {
        switch byte {
        case 0x61...0x7A: return byte - 0x60 // a-z -> 1...26
        case 0x41...0x5A: return byte - 0x40 // A-Z -> 1...26
        default: return byte & 0x1f
        }
    }

    // MARK: - TerminalViewDelegate

    func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        onResize?(newCols, newRows)
    }

    func setTerminalTitle(source: TerminalView, title: String) {
        // The screen title is the connection profile's name; OSC 2 from the shell is
        // ignored, matching Android which never forwards it either.
    }

    func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {
        // Not surfaced in this screen's UI.
    }

    /// Soft/hard-keyboard keystrokes from the terminal arrive here. The CTRL toggle is
    /// applied first, then everything is forwarded to the SSH layer.
    func send(source: TerminalView, data: ArraySlice<UInt8>) {
        var bytes = Array(data)
        if ctrlDown {
            bytes = bytes.map(applyControl)
        }
        onSend(bytes)
    }

    func scrolled(source: TerminalView, position: Double) {
        // No status/scrollbar UI to update.
    }

    func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
        // Deliberately ignored for now; opening links mid-session is surprising.
    }

    func rangeChanged(source: TerminalView, startY: Int, endY: Int) {
        // The renderer repaints itself; nothing to forward.
    }
}

// MARK: - PasteAwareTerminalView

/// A TerminalView subclass that cleans up pasted text before sending it to the PTY, matching
/// Android Termux's `TerminalEmulator.paste`:
///   1. Strip ESC (0x1B) and C1 control chars (0x80-0x9F) — these would trigger escape
///      sequences and garble the screen when pasted from formatted sources.
///   2. Convert LF (0x0A) and CRLF to CR (0x0D) — a raw PTY needs carriage returns to execute
///      commands; bare LFs just move the cursor down, so multi-line paste piles onto one line
///      and the output looks scrambled ("错位").
///
/// SwiftTerm's default `paste` forwards the clipboard verbatim, which is why pasting multi-line
/// text produced garbled output. Normal keyboard input goes through `send(source:data:)`, not
/// `paste`, so this only affects clipboard pastes.
private final class PasteAwareTerminalView: TerminalView {
    @objc override func paste(_ sender: Any?) {
        guard let raw = UIPasteboard.general.string, !raw.isEmpty else { return }

        // Remove ESC and C1 controls.
        var cleaned = String(raw.unicodeScalars.filter {
            $0 != "\u{001B}" && !($0.value >= 0x80 && $0.value <= 0x9F)
        })
        // LF / CRLF → CR.
        cleaned = cleaned.replacingOccurrences(of: "\r\n", with: "\r")
        cleaned = cleaned.replacingOccurrences(of: "\n", with: "\r")

        // Bracketed paste if the program enabled it (matches SwiftTerm's original behaviour).
        if getTerminal().bracketedPasteMode {
            send(data: EscapeSequences.bracketedPasteStart[...])
        }
        send(txt: cleaned)
        if getTerminal().bracketedPasteMode {
            send(data: EscapeSequences.bracketedPasteEnd[...])
        }
    }
}

// MARK: - ExtraKeysAccessoryBar

/// A fixed-height bar of extra keys (ESC/CTRL/arrows/TAB/…), mounted as the terminal's
/// `inputAccessoryView` so it floats just above the soft keyboard — replacing SwiftTerm's
/// built-in `TerminalAccessory`. The keys scroll horizontally since there are more than fit
/// on a phone width.
///
/// Subclasses `UIInputView` (the class Apple designed for input accessory views) so UIKit
/// resolves the bar's height correctly. A plain `UIView` as `inputAccessoryView` is unreliable
/// on iOS — UIKit can collapse it to zero height, which is why an earlier attempt showed no keys.
private final class ExtraKeysAccessoryBar: UIView {

    private let scrollView = UIScrollView()
    private let stack = UIStackView()

    /// On regular width (iPad) the key strip is centered and capped at this width so it reads as
    /// a single toolbar floating above the keyboard, not a row stretched across the full screen.
    /// On compact width (iPhone) the strip fills the bar and scrolls — there are more keys than
    /// fit on a phone. Matches the iPad soft-keyboard width closely.
    private static let regularMaxWidth: CGFloat = 760

    /// Height of the bar — kept tight (36pt) to match the system keyboard's row height and
    /// minimize dead space between the terminal and the keyboard.
    static let barHeight: CGFloat = 36

    /// Leading/trailing constraints of the scrollView, swapped in `applyWidthClass` so the strip
    /// is full-width on phone and centered+bounded on iPad.
    private var scrollLeading: NSLayoutConstraint!
    private var scrollTrailing: NSLayoutConstraint!
    private var scrollCenterX: NSLayoutConstraint!
    /// iPad-only "centered and capped" constraints (less-than-or-equal edges + max width).
    private var scrollLeadingLTE: NSLayoutConstraint!
    private var scrollTrailingLTE: NSLayoutConstraint!
    private var scrollMaxWidth: NSLayoutConstraint!

    override init(frame: CGRect) {
        super.init(frame: frame)
        // Match the terminal's black so the bar blends into it with no visible seam; the buttons
        // (slightly lighter) provide the visual separation.
        backgroundColor = .black

        scrollView.showsHorizontalScrollIndicator = false
        // delaysContentTouches MUST stay true (the default): it lets the scroll view detect a
        // horizontal drag (scroll) before forwarding the touch to a button. Setting it false
        // (an earlier mistake) forwarded every touch straight to the button, so the scroll view
        // never saw a pan and the row wouldn't scroll — even though contentSize > frame.
        scrollView.delaysContentTouches = true
        scrollView.canCancelContentTouches = true
        scrollView.alwaysBounceHorizontal = true
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scrollView)

        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)

        // scrollView occupies only the top `barHeight` points of the bar — the area below (the
        // bottom safe-area inset / home indicator zone) stays as empty black fill, so the bar
        // reaches the screen edge visually but the buttons sit above the home indicator.
        scrollLeading = scrollView.leadingAnchor.constraint(equalTo: leadingAnchor)
        scrollTrailing = scrollView.trailingAnchor.constraint(equalTo: trailingAnchor)
        // Centered alternative, activated only on regular width (see applyWidthClass).
        scrollCenterX = scrollView.centerXAnchor.constraint(equalTo: centerXAnchor)
        scrollLeadingLTE = scrollView.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor)
        scrollTrailingLTE = scrollView.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor)
        scrollMaxWidth = scrollView.widthAnchor.constraint(lessThanOrEqualToConstant: Self.regularMaxWidth)
        scrollCenterX.priority = .required

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.heightAnchor.constraint(equalToConstant: Self.barHeight),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 6),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -6),
            stack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),
        ])

        applyWidthClass()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// Fixed height for Auto Layout (the bar is sized by an explicit constraint too).
    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: Self.barHeight)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.horizontalSizeClass != traitCollection.horizontalSizeClass {
            applyWidthClass()
        }
    }

    /// Swaps the scrollView's horizontal layout for the current size class. Phone (compact): the
    /// strip spans the bar and scrolls when keys overflow. iPad (regular): the strip is centered
    /// and capped at `regularMaxWidth` so it floats as a toolbar above the keyboard.
    private func applyWidthClass() {
        let regular = traitCollection.horizontalSizeClass == .regular
        // Phone (compact): strip spans the full bar.
        scrollLeading.isActive = !regular
        scrollTrailing.isActive = !regular
        // iPad (regular): strip centered and capped at regularMaxWidth, with >=/<= edges so it
        // never overflows the bar even on a narrow Slide Over window.
        scrollCenterX.isActive = regular
        scrollLeadingLTE.isActive = regular
        scrollTrailingLTE.isActive = regular
        scrollMaxWidth.isActive = regular
    }

    /// Populates the key row. The closures forward to the controller:
    /// - `send`: raw bytes straight to SSH.
    /// - `setCtrl`: toggles the Ctrl modifier for soft-keyboard input.
    /// - `cursor`: arrow/Home/End final byte, resolved against application-cursor mode.
    /// - `tmux`: tmux prefix + a window-management key.
    func configure(
        config: ConnectionConfig,
        send: @escaping ([UInt8]) -> Void,
        setCtrl: @escaping (Bool) -> Void,
        cursor: @escaping (UInt8) -> Void,
        tmux: @escaping (String) -> Void,
        hideKeyboard: @escaping () -> Void
    ) {
        // Order matches Android's `buildExtraKeysRow`: ESC, then cursor keys early (the row
        // scrolls, and these are used constantly for shell history / TUI nav).
        addKey("ESC", .press) { _ in send([0x1B]) }
        addKey("CTRL", .toggle) { btn in setCtrl(btn.isToggledOn) }
        addKey("←", .repeatable) { _ in cursor(0x44) } // D
        addKey("↓", .repeatable) { _ in cursor(0x42) } // B
        addKey("↑", .repeatable) { _ in cursor(0x41) } // A
        addKey("→", .repeatable) { _ in cursor(0x43) } // C
        if config.tmuxSession.isNotBlank {
            addKey("W+", .press) { _ in tmux("c") }
            addKey("◀W", .press) { _ in tmux("p") }
            addKey("W▶", .press) { _ in tmux("n") }
        }
        addKey("TAB", .press) { _ in send([0x09]) }
        addKey("S-TAB", .press) { _ in send([0x1B, 0x5B, 0x5A]) }
        addKey("^C", .press) { _ in send([0x03]) }
        addKey("^D", .press) { _ in send([0x04]) }
        addKey("Home", .press) { _ in cursor(0x48) } // H
        addKey("End", .press) { _ in cursor(0x46) } // F
        addKey("PgUp", .repeatable) { _ in send([0x1B, 0x5B, 0x35, 0x7E]) }
        addKey("PgDn", .repeatable) { _ in send([0x1B, 0x5B, 0x36, 0x7E]) }
        addKey("Enter", .press) { _ in send([0x0D]) }
        addKey("y", .press) { _ in send([0x79]) }
        addKey("n", .press) { _ in send([0x6E]) }
        // Keyboard dismiss: drops the soft keyboard so the terminal is larger; tapping the
        // terminal area (or the key again) re-shows it.
        addKey("⌨", .press) { _ in hideKeyboard() }
    }

    private func addKey(_ label: String, _ behavior: ExtraKeyButton.Behavior, _ action: @escaping (ExtraKeyButton) -> Void) {
        let button = ExtraKeyButton(behavior: behavior)
        button.setTitle(label, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        // Tight padding (4pt vertical) so the bar stays slim and matches the keyboard's density;
        // the bar height is only 36pt so there's no room for generous insets.
        button.contentEdgeInsets = UIEdgeInsets(top: 4, left: 12, bottom: 4, right: 12)
        button.layer.cornerRadius = 4
        button.layer.masksToBounds = true
        button.backgroundColor = ExtraKeyButton.normalBackground
        switch behavior {
        case .press, .repeatable:
            button.onPress = { action(button) }
        case .toggle:
            button.onToggleChange = { _ in action(button) }
        }
        stack.addArrangedSubview(button)
    }
}

// MARK: - ExtraKeyButton

/// One key in the extra-keys row, mirroring the three Android behaviours in
/// `TerminalActivity`:
/// - `.press`: fires once per tap.
/// - `.repeatable`: fires on touch-down and keeps firing while held. The first repeat
///   waits out `keyRepeatDelay` so a normal tap stays a single keypress, then it fires
///   every `keyRepeatInterval`.
/// - `.toggle`: sticky on/off state signalled through `onToggleChange`.
///
/// Repeat is driven by a `Timer` on the common run-loop mode and cancelled on touch-up
/// and on cancel — a scroll of the key row delivers the latter, and missing it would
/// leave the key repeating forever (the same bug Android guards against in
/// `ACTION_CANCEL`).
private final class ExtraKeyButton: UIButton {

    enum Behavior {
        case press
        case repeatable
        case toggle
    }

    static let normalBackground = UIColor(white: 0.16, alpha: 1)
    static let activeBackground = UIColor(white: 0.42, alpha: 1)

    let behavior: Behavior
    var onPress: () -> Void = {}
    var onToggleChange: (Bool) -> Void = { _ in }

    private var repeatTimer: Timer?
    private var toggled = false {
        didSet { onToggleChange(toggled) }
    }

    /// Current on/off state for `.toggle` buttons (read after a toggle change).
    var isToggledOn: Bool { toggled }

    init(behavior: Behavior) {
        self.behavior = behavior
        super.init(frame: .zero)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit {
        repeatTimer?.invalidate()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        backgroundColor = Self.activeBackground
        if behavior == .repeatable {
            fire()
            scheduleRepeat()
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        stopRepeat()
        // Count only releases that happen inside the button — sliding off cancels the tap.
        let endedInside = touches.first.map { bounds.contains($0.location(in: self)) } ?? false
        super.touchesEnded(touches, with: event)
        switch behavior {
        case .press:
            if endedInside { fire() }
        case .toggle:
            if endedInside { toggled.toggle() }
        case .repeatable:
            break
        }
        restoreBackground()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        stopRepeat()
        super.touchesCancelled(touches, with: event)
        restoreBackground()
    }

    private func fire() {
        onPress()
    }

    private func scheduleRepeat() {
        let timer = Timer(timeInterval: keyRepeatInterval, repeats: true) { [weak self] _ in
            self?.fire()
        }
        timer.fireDate = Date().addingTimeInterval(keyRepeatDelay)
        RunLoop.main.add(timer, forMode: .common)
        repeatTimer = timer
    }

    private func stopRepeat() {
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    private func restoreBackground() {
        backgroundColor = (behavior == .toggle && toggled) ? Self.activeBackground : Self.normalBackground
    }
}

// MARK: - String helpers

private extension String {
    /// Kotlin's `String.isNotBlank` — whitespace-only strings count as blank.
    var isNotBlank: Bool {
        !trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
