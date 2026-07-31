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
final class TerminalViewController: UIViewController, TerminalViewDelegate {

    // MARK: - Wired up by the SSH layer

    /// The connection profile this terminal is attached to.
    let config: ConnectionConfig

    /// Receives keystrokes — extra-row taps and soft-keyboard input — destined for the
    /// remote shell. The SSH connector sets this to write into its channel.
    var onSend: ([UInt8]) -> Void = { _ in }

    /// Called when the terminal grid size changes so the caller can send a PTY
    /// window-change (Android: `SshTerminalConnector`'s window-change hook).
    var onResize: ((_ cols: Int, _ rows: Int) -> Void)?

    /// Called when the user taps the status banner to force a reconnect.
    var onReconnect: () -> Void = {}

    /// Called when this screen is going away so the caller can tear down the SSH channel.
    /// Never blocks the main thread here — the caller handles that asynchronously, exactly
    /// like Android's `disconnectInBackground()`.
    var onDisconnect: () -> Void = {}

    // MARK: - Status strings (mirror Android's values-zh strings.xml)

    static let statusConnecting = "连接中…"
    static let statusReconnecting = "重连中…"
    static let statusTunnelStarting = "隧道启动中…"
    static let statusDisconnected = "已断开"

    // MARK: - Views

    private let terminalView = TerminalView(frame: .zero)
    private let extraKeysScrollView = UIScrollView()
    private let extraKeysStack = UIStackView()
    private let statusLabel = UILabel()

    /// Ctrl modifier toggled from the extra-keys row. Applied to soft-keyboard input in
    /// `send(source:data:)`, mirroring Android's `AppTerminalViewClient.ctrlDown`.
    private var ctrlDown = false

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
        title = config.displayName

        setupTerminalView()
        setupExtraKeysRow()
        setupStatusLabel()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Like Android's `requestFocus()`: opens the soft keyboard right away.
        terminalView.becomeFirstResponder()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        onDisconnect()
    }

    // MARK: - Remote output

    /// Feeds remote stdout bytes into the emulator. The SSH reader runs on a background
    /// thread, but `Terminal` isn't thread-safe, so the feed is marshalled to the main
    /// thread (the main queue is serial, so chunk order is preserved).
    func feed(_ bytes: [UInt8]) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.terminalView.getTerminal().feed(byteArray: bytes)
        }
    }

    // MARK: - Status banner

    /// Shows the connection-status banner; tapping it reconnects (Android's `textConnectionStatus`).
    func showStatus(_ text: String) {
        statusLabel.text = text
        statusLabel.isHidden = false
    }

    func hideStatus() {
        statusLabel.isHidden = true
    }

    // MARK: - Layout

    private func setupTerminalView() {
        terminalView.terminalDelegate = self
        terminalView.backgroundColor = .black
        terminalView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(terminalView)

        NSLayoutConstraint.activate([
            terminalView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            terminalView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            terminalView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            terminalView.bottomAnchor.constraint(equalTo: extraKeysScrollView.topAnchor),
        ])
    }

    /// A horizontally scrolling row of keys below the terminal, ordered exactly like
    /// Android's `buildExtraKeysRow`.
    private func setupExtraKeysRow() {
        extraKeysScrollView.showsHorizontalScrollIndicator = false
        extraKeysScrollView.delaysContentTouches = false
        extraKeysScrollView.backgroundColor = ExtraKeyButton.normalBackground
        extraKeysScrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(extraKeysScrollView)

        extraKeysStack.axis = .horizontal
        extraKeysStack.alignment = .center
        extraKeysStack.spacing = 4
        extraKeysStack.translatesAutoresizingMaskIntoConstraints = false
        extraKeysScrollView.addSubview(extraKeysStack)

        NSLayoutConstraint.activate([
            extraKeysScrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            extraKeysScrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            extraKeysScrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            extraKeysScrollView.heightAnchor.constraint(equalToConstant: 46),

            extraKeysStack.topAnchor.constraint(equalTo: extraKeysScrollView.contentLayoutGuide.topAnchor),
            extraKeysStack.leadingAnchor.constraint(equalTo: extraKeysScrollView.contentLayoutGuide.leadingAnchor, constant: 4),
            extraKeysStack.trailingAnchor.constraint(equalTo: extraKeysScrollView.contentLayoutGuide.trailingAnchor, constant: -4),
            extraKeysStack.bottomAnchor.constraint(equalTo: extraKeysScrollView.contentLayoutGuide.bottomAnchor),
            extraKeysStack.heightAnchor.constraint(equalTo: extraKeysScrollView.frameLayoutGuide.heightAnchor),
        ])

        buildExtraKeysRow()
    }

    private func setupStatusLabel() {
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor(red: 0xB3 / 255, green: 0x6B / 255, blue: 0, alpha: 1)
        statusLabel.isHidden = true
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            statusLabel.heightAnchor.constraint(equalToConstant: 32),
        ])

        statusLabel.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(statusLabelTapped)))
    }

    @objc private func statusLabelTapped() {
        onReconnect()
    }

    // MARK: - Extra keys row

    private func buildExtraKeysRow() {
        // Plain bytes: ESC, then the cursor keys early on because the row scrolls
        // horizontally and these are the ones used constantly (shell history, TUI nav).
        addKey("ESC") { $0.sendBytes([0x1B]) }
        addToggleKey("CTRL") { $0.ctrlDown = $1 }
        addRepeatableKey("←") { $0.arrowKey(.left) }
        addRepeatableKey("↓") { $0.arrowKey(.down) }
        addRepeatableKey("↑") { $0.arrowKey(.up) }
        addRepeatableKey("→") { $0.arrowKey(.right) }
        // Only for sessions that actually have windows: in a plain shell these would type
        // the prefix as a stray control character.
        if config.tmuxSession.isNotBlank {
            addKey("W+") { $0.sendTmuxKey("c") }
            addKey("◀W") { $0.sendTmuxKey("p") }
            addKey("W▶") { $0.sendTmuxKey("n") }
        }
        addKey("TAB") { $0.sendBytes([0x09]) }
        // Back-tab (terminfo kcbt), what shift+tab produces on a real keyboard.
        addKey("S-TAB") { $0.sendBytes([0x1B, 0x5B, 0x5A]) }
        addKey("^C") { $0.sendBytes([0x03]) }
        addKey("^D") { $0.sendBytes([0x04]) }
        addKey("Home") { $0.cursorKey(.home) }
        addKey("End") { $0.cursorKey(.end) }
        addRepeatableKey("PgUp") { $0.sendBytes([0x1B, 0x5B, 0x35, 0x7E]) }
        addRepeatableKey("PgDn") { $0.sendBytes([0x1B, 0x5B, 0x36, 0x7E]) }
        addKey("Enter") { $0.sendBytes([0x0D]) }
        addKey("y") { $0.sendBytes([0x79]) }
        addKey("n") { $0.sendBytes([0x6E]) }
    }

    private func makeKeyButton(_ label: String, behavior: ExtraKeyButton.Behavior) -> ExtraKeyButton {
        let button = ExtraKeyButton(behavior: behavior)
        button.setTitle(label, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 15)
        button.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
        button.layer.cornerRadius = 6
        button.layer.masksToBounds = true
        button.backgroundColor = ExtraKeyButton.normalBackground
        return button
    }

    private func addKey(_ label: String, _ action: @escaping (TerminalViewController) -> Void) {
        let button = makeKeyButton(label, behavior: .press)
        button.onPress = { [weak self] in
            guard let self else { return }
            action(self)
        }
        extraKeysStack.addArrangedSubview(button)
    }

    private func addRepeatableKey(_ label: String, _ action: @escaping (TerminalViewController) -> Void) {
        let button = makeKeyButton(label, behavior: .repeatable)
        button.onPress = { [weak self] in
            guard let self else { return }
            action(self)
        }
        extraKeysStack.addArrangedSubview(button)
    }

    private func addToggleKey(_ label: String, _ action: @escaping (TerminalViewController, Bool) -> Void) {
        let button = makeKeyButton(label, behavior: .toggle)
        button.onToggleChange = { [weak self] toggled in
            guard let self else { return }
            action(self, toggled)
        }
        extraKeysStack.addArrangedSubview(button)
    }

    // MARK: - Sending keys

    /// Sends raw bytes to the remote shell, bypassing the CTRL modifier — extra-row keys
    /// mirror Android's `session.write`, which never consults `readControlKey()`.
    private func sendBytes(_ bytes: [UInt8]) {
        onSend(bytes)
    }

    /// Final byte of a cursor-key sequence, matching Termux's `KeyHandler.getCode`.
    private enum CursorKey: UInt8 {
        case up = 0x41 // A
        case down = 0x42 // B
        case right = 0x43 // C
        case left = 0x44 // D
        case home = 0x48 // H
        case end = 0x46 // F
    }

    /// Sends an arrow key. Arrows and Home/End have two encodings — `ESC [ x` normally and
    /// `ESC O x` once the foreground program switches the terminal into application-cursor
    /// mode (DECCKM), which every full-screen TUI does (vim, less, htop, Claude Code).
    /// SwiftTerm exposes the mode directly via `Terminal.applicationCursor`; this replaces
    /// the Android path that had the Termux emulator resolve it internally.
    private func arrowKey(_ direction: CursorKey) {
        sendCursorKey(direction.rawValue)
    }

    private func cursorKey(_ key: CursorKey) {
        sendCursorKey(key.rawValue)
    }

    private func sendCursorKey(_ finalByte: UInt8) {
        let leader: UInt8 = terminalView.getTerminal().applicationCursor ? 0x4F : 0x5B // ESC O / ESC [
        sendBytes([0x1B, leader, finalByte])
    }

    /// Sends tmux's prefix followed by `key`, which is how every tmux binding is invoked.
    /// The prefix is whatever the user configured — Ctrl-B unless they remapped it,
    /// commonly to Ctrl-A — read from UserDefaults so a settings screen can write it later
    /// (Android reads `AppSettings.tmuxPrefix`).
    private func sendTmuxKey(_ key: String) {
        guard config.tmuxSession.isNotBlank else { return }
        // Ctrl-<letter> is the letter's position in the alphabet: Ctrl-A is 1, Ctrl-B is 2.
        let control = UInt8(tmuxPrefix.asciiValue! - Character("a").asciiValue! + 1)
        sendBytes([control])
        sendBytes(Array(key.utf8))
    }

    private static let tmuxPrefixDefaultsKey = "tmuxPrefix"

    /// The letter of tmux's prefix key (the "b" in Ctrl-B). Defaults to "b".
    private var tmuxPrefix: Character {
        if let stored = UserDefaults.standard.string(forKey: Self.tmuxPrefixDefaultsKey)?.lowercased().first,
           stored >= "a" && stored <= "z" {
            return stored
        }
        return "b"
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
