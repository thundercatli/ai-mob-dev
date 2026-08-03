import XCTest
@testable import AiDevMob

final class ConfigBackupCryptoTests: XCTestCase {
    private let passphrase = "correct horse battery staple"
    private let salt = Data(hex: "000102030405060708090a0b0c0d0e0f")
    private let nonce = Data(hex: "101112131415161718191a1b")
    private let plainText = Data(base64Encoded:
        "eyJjb25uZWN0aW9ucyI6W10sImNyZWRlbnRpYWxzIjpbXSwic2VydmVycyI6W10sInNldHRpbmdzIjp7ImtlZXBTY3JlZW5PbiI6dHJ1ZSwidGVybWluYWxGb250U2l6ZSI6MTN9LCJ0dW5uZWxzIjpbXX0="
    )!
    private let androidSealed = Data(base64Encoded:
        "Ys3Wlt78D/qz6oOw9iKstb5jmMxBVKclmXtMg39OS9Vch5LDz7d4mCozRcAOFpFwtaIZweXNMlssoNz0YQrmm5vF23st8rzWTlLkNc3YQxYWkC7MADecDrswz3ppOFtIfQp/I/oo4qAsUwEOrzrWL1A7Rj5beNbbaBlQipG0XDGrsWij"
    )!

    /// Vector generated independently with Node/OpenSSL using the same primitives as Java's
    /// PBKDF2WithHmacSHA256 + AES/GCM/NoPadding implementation on Android.
    func testMatchesAndroidEncryptionVector() throws {
        let sealed = try ConfigBackup.seal(
            plainText,
            passphrase: passphrase,
            salt: salt,
            nonce: nonce,
            iterations: 210_000
        )
        XCTAssertEqual(sealed, androidSealed)

        let opened = try ConfigBackup.open(
            androidSealed,
            passphrase: passphrase,
            salt: salt,
            nonce: nonce,
            iterations: 210_000
        )
        XCTAssertEqual(opened, plainText)
    }

    func testWrongPassphraseFailsAuthentication() {
        XCTAssertThrowsError(try ConfigBackup.open(
            androidSealed,
            passphrase: "definitely wrong",
            salt: salt,
            nonce: nonce,
            iterations: 210_000
        )) { error in
            guard case ConfigBackup.BackupError.wrongPassphrase = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
    }

    func testRejectsMalformedEnvelope() {
        XCTAssertThrowsError(try ConfigBackup.restore(
            data: Data("not json".utf8),
            passphrase: passphrase
        )) { error in
            guard case ConfigBackup.BackupError.invalidFormat = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
    }

    func testRejectsNewerBackupVersion() throws {
        let backup = try ConfigBackup.exportData(passphrase: passphrase)
        var envelope = try XCTUnwrap(
            JSONSerialization.jsonObject(with: backup) as? [String: Any]
        )
        envelope["version"] = 2
        let newerBackup = try JSONSerialization.data(withJSONObject: envelope)

        XCTAssertThrowsError(try ConfigBackup.restore(
            data: newerBackup,
            passphrase: passphrase
        )) { error in
            guard case ConfigBackup.BackupError.invalidFormat(let message) = error else {
                return XCTFail("unexpected error: \(error)")
            }
            XCTAssertTrue(message.contains("2"))
        }
    }
}

private extension Data {
    init(hex: String) {
        precondition(hex.count.isMultiple(of: 2))
        self.init(stride(from: 0, to: hex.count, by: 2).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            let end = hex.index(start, offsetBy: 2)
            return UInt8(hex[start..<end], radix: 16)!
        })
    }
}
