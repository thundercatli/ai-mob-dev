import XCTest
@testable import AiDevMob

final class UpdateCheckerTests: XCTestCase {
    override func tearDown() {
        MockURLProtocol.handler = nil
        super.tearDown()
    }

    func testComparesNumericVersionsLikeAndroid() {
        XCTAssertGreaterThan(UpdateChecker.compareVersions("0.2.10", "0.2.9"), 0)
        XCTAssertEqual(UpdateChecker.compareVersions("v1.2", "1.2.0"), 0)
        XCTAssertLessThan(UpdateChecker.compareVersions("1.9.9", "2.0.0"), 0)
        XCTAssertEqual(UpdateChecker.compareVersions("1.2.3-beta", "1.2.3"), 0)
    }

    func testBuildsDirectAndMirrorEndpoints() throws {
        let source = try XCTUnwrap(URL(string: "https://api.github.com/repos/all3n/ai-mob-dev/releases/latest"))
        let endpoints = UpdateChecker.endpoints(for: source)

        XCTAssertEqual(endpoints.first, source)
        XCTAssertEqual(
            endpoints.last?.absoluteString,
            "https://p.all3n.top/api.github.com/repos/all3n/ai-mob-dev/releases/latest"
        )
    }

    func testParsesAvailableRelease() throws {
        let data = Data("""
        {
          "tag_name": "v0.2.3",
          "html_url": "https://github.com/all3n/ai-mob-dev/releases/tag/v0.2.3",
          "body": "Changes"
        }
        """.utf8)

        let outcome = try UpdateChecker.parseLatest(data, currentVersion: "0.2.2")
        guard case .updateAvailable(let release) = outcome else {
            return XCTFail("Expected an available update")
        }
        XCTAssertEqual(release.version, "v0.2.3")
        XCTAssertEqual(release.notes, "Changes")
    }

    func testTreatsSameOrOlderReleaseAsUpToDate() throws {
        let data = Data("""
        { "tag_name": "v0.2.3", "html_url": "https://example.com", "body": "" }
        """.utf8)

        XCTAssertEqual(
            try UpdateChecker.parseLatest(data, currentVersion: "0.2.3"),
            .upToDate(latestVersion: "v0.2.3")
        )
        XCTAssertEqual(
            try UpdateChecker.parseLatest(data, currentVersion: "0.3.0"),
            .upToDate(latestVersion: "v0.2.3")
        )
    }

    func testFallsBackToMirrorAfterTransportFailure() async throws {
        let recorder = RequestRecorder()
        MockURLProtocol.handler = { request in
            let host = try XCTUnwrap(request.url?.host)
            recorder.append(host)
            if host == "api.github.com" {
                throw URLError(.notConnectedToInternet)
            }

            let response = try XCTUnwrap(
                HTTPURLResponse(
                    url: try XCTUnwrap(request.url),
                    statusCode: 200,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )
            )
            let body = Data("""
            { "tag_name": "v0.2.3", "html_url": "https://example.com", "body": "Changes" }
            """.utf8)
            return (response, body)
        }

        let outcome = await makeChecker().check(currentVersion: "0.2.2", token: nil)
        guard case .updateAvailable(let release) = outcome else {
            return XCTFail("Expected the mirror response to provide an update")
        }
        XCTAssertEqual(release.version, "v0.2.3")
        XCTAssertEqual(recorder.values, ["api.github.com", "p.all3n.top"])
    }

    func testDoesNotRetryDefinitiveRateLimitResponse() async throws {
        let recorder = RequestRecorder()
        MockURLProtocol.handler = { request in
            let url = try XCTUnwrap(request.url)
            recorder.append(try XCTUnwrap(url.host))
            let response = try XCTUnwrap(
                HTTPURLResponse(url: url, statusCode: 403, httpVersion: nil, headerFields: nil)
            )
            return (response, Data())
        }

        let outcome = await makeChecker().check(currentVersion: "0.1.0", token: " token ")
        guard case .failed(let message) = outcome else {
            return XCTFail("Expected a definitive failure")
        }
        XCTAssertTrue(message.contains("403"))
        XCTAssertEqual(recorder.values, ["api.github.com"])
    }

    private func makeChecker() -> UpdateChecker {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return UpdateChecker(session: URLSession(configuration: configuration))
    }
}

private final class RequestRecorder {
    private let lock = NSLock()
    private var storage: [String] = []

    var values: [String] {
        lock.withLock { storage }
    }

    func append(_ value: String) {
        lock.withLock { storage.append(value) }
    }
}

private final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
