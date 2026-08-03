import Foundation

enum UpdateTokenStore {
    static let keychainAccount = "backup.androidUpdateToken"

    static var token: String? {
        get { KeychainHelper.get(keychainAccount)?.nilIfBlank }
        set { KeychainHelper.set(newValue?.nilIfBlank, for: keychainAccount) }
    }
}

struct UpdateChecker {
    static let repository = "all3n/ai-mob-dev"
    static let releasesURL = URL(string: "https://github.com/\(repository)/releases")!
    static let mirrorHost = "p.all3n.top"

    private static let apiURL = URL(
        string: "https://api.github.com/repos/\(repository)/releases/latest"
    )!

    struct Release: Equatable, Identifiable {
        let version: String
        let pageURL: URL
        let notes: String?

        var id: String { version }
    }

    enum Outcome: Equatable {
        case updateAvailable(Release)
        case upToDate(latestVersion: String)
        case failed(message: String)
    }

    private enum Attempt {
        case done(Outcome)
        case retry(String)
    }

    private struct GitHubRelease: Decodable {
        let tagName: String
        let pageURL: URL?
        let notes: String?

        private enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case pageURL = "html_url"
            case notes = "body"
        }
    }

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func check(currentVersion: String, token: String?) async -> Outcome {
        var firstError: String?
        for endpoint in Self.endpoints(for: Self.apiURL) {
            switch await query(endpoint, currentVersion: currentVersion, token: token) {
            case .done(let outcome):
                return outcome
            case .retry(let message):
                if firstError == nil { firstError = message }
            }
        }
        return .failed(message: firstError ?? "未知错误")
    }

    static func mirrored(_ url: URL) -> URL {
        URL(string: "https://\(mirrorHost)/\(url.absoluteString.removingHTTPPrefix)")!
    }

    static func endpoints(for url: URL) -> [URL] {
        [url, mirrored(url)]
    }

    static func compareVersions(_ left: String, _ right: String) -> Int {
        let leftParts = numericParts(left)
        let rightParts = numericParts(right)
        for index in 0..<max(leftParts.count, rightParts.count) {
            let comparison = leftParts[safe: index, default: 0]
                .compare(rightParts[safe: index, default: 0])
            if comparison != 0 { return comparison }
        }
        return 0
    }

    static func parseLatest(_ data: Data, currentVersion: String) throws -> Outcome {
        let release = try JSONDecoder().decode(GitHubRelease.self, from: data)
        guard !release.tagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw UpdateError.missingTag
        }

        let latest = release.tagName.removingLeadingV
        if compareVersions(latest, currentVersion) > 0 {
            return .updateAvailable(
                Release(
                    version: release.tagName,
                    pageURL: release.pageURL ?? releasesURL,
                    notes: release.notes?.nilIfBlank
                )
            )
        }
        return .upToDate(latestVersion: release.tagName)
    }

    private func query(_ url: URL, currentVersion: String, token: String?) async -> Attempt {
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "GET"
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let token = token?.nilIfBlank {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse else {
                return .retry("服务器返回了无效响应")
            }

            switch response.statusCode {
            case 200:
                do {
                    return .done(try Self.parseLatest(data, currentVersion: currentVersion))
                } catch {
                    return .retry("无法解析版本信息：\(error.localizedDescription)")
                }
            case 401, 403, 429:
                return .done(.failed(message: "GitHub 请求受限（HTTP \(response.statusCode)）"))
            case 404:
                return .done(.failed(message: "没有找到发布版本"))
            default:
                return .retry("服务器返回 HTTP \(response.statusCode)")
            }
        } catch {
            return .retry(error.localizedDescription)
        }
    }

    private static func numericParts(_ version: String) -> [Int] {
        version.trimmingCharacters(in: .whitespacesAndNewlines)
            .removingLeadingV
            .split(whereSeparator: { $0 == "." || $0 == "-" || $0 == "+" })
            .compactMap { component in
                let digits = component.prefix(while: \Character.isNumber)
                return Int(digits)
            }
    }

    private enum UpdateError: LocalizedError {
        case missingTag

        var errorDescription: String? { "发布版本缺少 tag" }
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    var removingLeadingV: String {
        hasPrefix("v") ? String(dropFirst()) : self
    }

    var removingHTTPPrefix: String {
        replacingOccurrences(of: "^https?://", with: "", options: .regularExpression)
    }
}

private extension Array where Element == Int {
    subscript(safe index: Int, default fallback: Int) -> Int {
        indices.contains(index) ? self[index] : fallback
    }
}

private extension Int {
    func compare(_ other: Int) -> Int {
        self == other ? 0 : (self < other ? -1 : 1)
    }
}
