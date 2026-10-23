import Foundation
import Security
import CommonCrypto

/// Device-only Keychain record. PIN controls app access; iOS Data Protection encrypts the database.
final class PinVault {
    enum Failure: Error { case unavailable, keychain(OSStatus), rejected, invalid }
    private struct State: Codable { var enabled: Bool; var salt: Data; var hash: Data; var attempts: Int; var until: Double }
    private let service: String
    private let account: String
    init(service: String = "com.organizerpro.mobile.pin.v1", account: String = "device-pin") { self.service = service; self.account = account }
    func scoped(installation: String) -> PinVault { PinVault(service: service, account: "device-pin." + installation) }
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false] }
    private func read() throws -> State? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw Failure.keychain(status) }
        guard status == errSecSuccess, let data = result as? Data, let state = try? JSONDecoder().decode(State.self, from: data),
              state.salt.count == 16, state.hash.count == 32, (0..<5).contains(state.attempts), state.until.isFinite, state.until >= 0 else { throw Failure.unavailable }
        return state
    }
    var exists: Bool { (try? read()) != nil }
    func isEnabled() throws -> Bool { try read()?.enabled ?? false }
    func retrySeconds() throws -> Int { max(0, Int(ceil((try read()?.until ?? 0) - Date().timeIntervalSince1970))) }
    private func write(_ state: State) throws {
        let data = try JSONEncoder().encode(state)
        var attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let result = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if result == errSecItemNotFound {
            attrs.merge(query) { _, new in new }
            let added = SecItemAdd(attrs as CFDictionary, nil)
            guard added == errSecSuccess else { throw Failure.keychain(added) }
        } else if result != errSecSuccess { throw Failure.keychain(result) }
    }
    private func derive(_ pin: String, salt: Data) throws -> Data {
        var output = Data(count: 32)
        let result = output.withUnsafeMutableBytes { target in
            salt.withUnsafeBytes { bytes in
                pin.withCString { password in
                    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password, pin.utf8.count, bytes.bindMemory(to: UInt8.self).baseAddress, salt.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 210_000, target.bindMemory(to: UInt8.self).baseAddress, 32)
                }
            }
        }
        guard result == kCCSuccess else { throw Failure.unavailable }; return output
    }
    func set(pin: String) throws {
        guard (4...12).contains(pin.count), pin.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw Failure.invalid }
        var salt = Data(count: 16)
        guard salt.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }) == errSecSuccess else { throw Failure.unavailable }
        try write(State(enabled: true, salt: salt, hash: derive(pin, salt: salt), attempts: 0, until: 0))
    }
    func verify(_ pin: String) throws -> Bool {
        guard var state = try read(), state.enabled else { return false }
        let now = Date().timeIntervalSince1970
        guard state.until <= now else { return false }
        let actual = try derive(pin, salt: state.salt)
        var difference: UInt8 = 0
        for (a, b) in zip(actual, state.hash) { difference |= a ^ b }
        if difference == 0 { state.attempts = 0; state.until = 0 }
        else { state.attempts = state.until > 0 ? 1 : state.attempts + 1; state.until = 0; if state.attempts >= 5 { state.attempts = 0; state.until = now + 30 } }
        try write(state); return difference == 0
    }
    func disable() throws { guard var state = try read() else { return }; state.enabled = false; state.attempts = 0; state.until = 0; try write(state) }
}
