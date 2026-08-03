import Foundation
import Citadel
import NIO

/// One item returned by a remote SFTP directory listing.
struct RemoteFileEntry: Identifiable, Hashable, Sendable {
    var id: String { path }

    let name: String
    let path: String
    let isDirectory: Bool
    let isLink: Bool
    let size: UInt64
    let modified: Date?
    let permissions: UInt32
}

enum SftpSessionError: LocalizedError {
    case notConnected
    case invalidDownloadName

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "SSH 连接尚未就绪，请稍候重试。"
        case .invalidDownloadName:
            return "远端文件名无效，无法保存。"
        }
    }
}

/// A single long-lived SFTP subsystem. Actor isolation serializes every operation because SFTP
/// request/response ordering is stateful and navigation often happens over a high-latency tunnel.
actor SftpSession {
    static let maxTextPreviewBytes: UInt64 = 512 * 1024
    static let maxImagePreviewBytes: UInt64 = 12 * 1024 * 1024

    private static let readChunkBytes: UInt32 = 64 * 1024
    private let sshClient: SSHClient?
    private let sftp: SFTPClient
    private var isClosed = false

    private init(sshClient: SSHClient?, sftp: SFTPClient) {
        self.sshClient = sshClient
        self.sftp = sftp
    }

    /// Opens a standalone SSH connection and owns it for the browsing session.
    static func open(config: ConnectionConfig) async throws -> SftpSession {
        do {
            return try await openOnce(config: config)
        } catch {
            // frpc can report RUNNING just before its local listener starts accepting sockets.
            // Match the tmux probe's one short retry so a newly-started tunnel is reliable.
            try? await Task.sleep(nanoseconds: 400_000_000)
            return try await openOnce(config: config)
        }
    }

    private static func openOnce(config: ConnectionConfig) async throws -> SftpSession {
        let validator = TofuHostKeyValidator(host: config.host, port: config.port)
        let ssh = try await SSHClient.connect(
            host: config.host,
            port: config.port,
            authenticationMethod: try SshTerminalConnector.makeAuthenticationMethod(from: config),
            hostKeyValidator: SSHHostKeyValidator.custom(validator),
            reconnect: .never
        )

        do {
            let sftp = try await ssh.openSFTP()
            return SftpSession(sshClient: ssh, sftp: sftp)
        } catch {
            try? await ssh.close()
            throw error
        }
    }

    /// Adds an SFTP subsystem to an existing terminal SSH connection. The returned session owns
    /// only the subsystem; closing it leaves the PTY and parent SSH connection running.
    static func attach(to ssh: SSHClient) async throws -> SftpSession {
        SftpSession(sshClient: nil, sftp: try await ssh.openSFTP())
    }

    func homePath() async throws -> String {
        try ensureOpen()
        return try await sftp.getRealPath(atPath: ".")
    }

    func canonicalize(_ path: String) async throws -> String {
        try ensureOpen()
        return try await sftp.getRealPath(atPath: path)
    }

    func list(_ path: String) async throws -> [RemoteFileEntry] {
        try ensureOpen()
        let batches = try await sftp.listDirectory(atPath: path)
        var entries = [RemoteFileEntry]()

        for component in batches.flatMap(\.components) where component.filename != "." && component.filename != ".." {
            let entryPath = Self.join(path, component.filename)
            let permissions = component.attributes.permissions ?? 0
            let kind = permissions & 0o170000
            let isLink = kind == 0o120000 || component.longname.first == "l"
            var isDirectory = kind == 0o040000 || component.longname.first == "d"

            // SFTP listing attributes describe the link itself. stat() follows it, so links to
            // directories remain navigable just like they are in the Android implementation.
            if isLink, let target = try? await sftp.getAttributes(at: entryPath) {
                let targetPermissions = target.permissions ?? 0
                isDirectory = targetPermissions & 0o170000 == 0o040000
            }

            entries.append(RemoteFileEntry(
                name: component.filename,
                path: entryPath,
                isDirectory: isDirectory,
                isLink: isLink,
                size: component.attributes.size ?? 0,
                modified: component.attributes.accessModificationTime?.modificationTime,
                permissions: permissions & 0o777
            ))
        }

        return entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    /// Reads at most `limit` bytes and reports whether more remote data exists.
    func readBytes(at path: String, limit: UInt64) async throws -> (data: Data, truncated: Bool) {
        try ensureOpen()
        let attributes = try await sftp.getAttributes(at: path)
        let expected = min(attributes.size ?? limit, limit)

        let data = try await sftp.withFile(filePath: path, flags: .read) { file in
            var result = Data()
            var offset: UInt64 = 0

            while offset < expected {
                let requestLength = UInt32(min(UInt64(Self.readChunkBytes), expected - offset))
                let buffer = try await file.read(from: offset, length: requestLength)
                guard buffer.readableBytes > 0 else { break }
                result.append(contentsOf: buffer.readableBytesView)
                offset += UInt64(buffer.readableBytes)
            }
            return result
        }

        let truncated = attributes.size.map { $0 > UInt64(data.count) }
            ?? (UInt64(data.count) == limit)
        return (data, truncated)
    }

    /// Streams a remote file into a unique temporary location suitable for the iOS share sheet.
    func download(_ entry: RemoteFileEntry) async throws -> URL {
        try ensureOpen()
        let safeName = URL(fileURLWithPath: entry.name).lastPathComponent
        guard !safeName.isEmpty, safeName != ".", safeName != ".." else {
            throw SftpSessionError.invalidDownloadName
        }

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("aidevmob-downloads", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(safeName, isDirectory: false)
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }

        let handle = try FileHandle(forWritingTo: destination)
        do {
            try await sftp.withFile(filePath: entry.path, flags: .read) { file in
                var offset: UInt64 = 0
                while true {
                    let buffer = try await file.read(from: offset, length: Self.readChunkBytes)
                    guard buffer.readableBytes > 0 else { break }
                    try handle.write(contentsOf: Data(buffer.readableBytesView))
                    offset += UInt64(buffer.readableBytes)
                }
            }
            try handle.close()
            return destination
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    func close() async {
        guard !isClosed else { return }
        isClosed = true
        try? await sftp.close()
        try? await sshClient?.close()
    }

    private func ensureOpen() throws {
        if isClosed { throw SftpSessionError.notConnected }
    }

    private static func join(_ directory: String, _ name: String) -> String {
        if directory == "/" { return "/\(name)" }
        return "\(directory.hasSuffix("/") ? String(directory.dropLast()) : directory)/\(name)"
    }
}

func remoteParentPath(_ path: String) -> String? {
    guard path != "/", !path.isEmpty else { return nil }
    let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
    guard let slash = trimmed.lastIndex(of: "/") else { return nil }
    return slash == trimmed.startIndex ? "/" : String(trimmed[..<slash])
}
