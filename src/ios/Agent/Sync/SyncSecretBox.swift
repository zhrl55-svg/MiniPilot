import CryptoKit
import Foundation
import Security

private let logger = AppLogger(category: "SyncSecrets")

/// End-to-end encryption envelope (`enc:v1`) for secret fields on the iCloud
/// sync path.
///
/// Motivation: CloudKit records are ordinary cloud data (readable by Apple's
/// servers). `secretsJson` (v1 ProviderConfig/EnvVar) and `secretBlob` (v3
/// ProviderInstance) used to leave the device as plaintext/base64; this
/// envelope encrypts them at the CKRecord boundary so the key material never
/// travels in the clear.
///
/// Key hierarchy: a 256-bit random KEK, generated on first use and stored in
/// the Keychain with `kSecAttrSynchronizable = true`, so iCloud Keychain
/// propagates it to every device on the account — no pairing step needed.
///
/// Accessibility: `kSecAttrAccessibleAfterFirstUnlock` **without** the
/// ThisDeviceOnly suffix. Keychain semantics: items marked `...ThisDeviceOnly`
/// never leave the device by definition, which is mutually exclusive with
/// `kSecAttrSynchronizable`; synchronizable items only accept the
/// (non-ThisDeviceOnly) when-unlocked family. This is the exact combination
/// `ProviderKeychainHelper.saveAPIKey` already uses.
///
/// Wire format: `"enc:v1:" + base64(AES.GCM.SealedBox.combined)`.
/// `combined` already contains nonce(12B) ‖ ciphertext ‖ tag(16B) in one
/// buffer — no manual packing, and `SealedBox(combined:)` splits it back out.
///
/// KEK read strategy: every seal/unseal reads the Keychain fresh, no in-process
/// cache. Arrival order of iCloud Keychain sync is not controllable, so a cache
/// could go stale after the KEK item is replaced by a synced copy; the
/// per-call cost is one Keychain lookup (a few dozen calls per sync batch).
///
/// Fresh-device race: if this device seals before iCloud Keychain sync has
/// delivered the existing KEK, it generates a different one. The consequence is
/// bounded: mutual unseal fails → callers log and skip the field, and once the
/// Keychain converges (single item per service+account, last-writer-wins) the
/// next upload re-seals with the surviving KEK. Self-healing, never fatal.
///
/// Compatibility matrix (one-time transition; all devices run the same codebase):
///   - New device reads old record (plaintext payload) → `unsealString` returns
///     it verbatim, parsing proceeds as before.
///   - Old device reads new record (`enc:v1:`) → its existing parse fails → old
///     code silently skips the import. Acceptable one-time degradation, noted
///     at each call site.
enum SyncSecretBox {

    /// Envelope prefix. Consumers use it to distinguish ciphertext from a
    /// legacy plaintext payload.
    static let envelopePrefix = "enc:v1:"

    enum SyncSecretBoxError: LocalizedError {
        case keychainStatus(OSStatus)
        case randomGenerationFailed
        case sealedBoxUnavailable
        case corruptStoredKey

        var errorDescription: String? {
            switch self {
            case .keychainStatus(let s):
                return "SyncSecretBox keychain failure (status \(s))"
            case .randomGenerationFailed:
                return "SyncSecretBox could not generate a random KEK"
            case .sealedBoxUnavailable:
                return "SyncSecretBox: AES-GCM combined box unavailable"
            case .corruptStoredKey:
                return "SyncSecretBox: stored KEK has unexpected size"
            }
        }
    }

    // Service naming follows the repo convention `com.openminis.app.<domain>`.
    // Verified against every existing service (provider.<instanceId>, envvar,
    // device, mcp-oauth, rclone, legacy *-oauth / *-api-key / active-provider)
    // — no collision, nothing gets overwritten.
    private static let keychainService = "com.openminis.app.sync-secrets"
    private static let keychainAccount = "sync-kek-v1"

    // MARK: - Seal

    /// Encrypt arbitrary bytes → `"enc:v1:" + base64(nonce ‖ ciphertext ‖ tag)`.
    /// Callers catch and apply their own failure convention (log + drop the
    /// field, never crash, never abort the batch).
    static func seal(_ plaintext: Data) throws -> String {
        let sealed = try AES.GCM.seal(plaintext, using: kek())
        guard let combined = sealed.combined else {
            throw SyncSecretBoxError.sealedBoxUnavailable
        }
        return envelopePrefix + combined.base64EncodedString()
    }

    // MARK: - Unseal

    /// Inverse of `seal`. Input without the prefix is treated as a legacy
    /// plaintext base64 payload and decoded as-is (backward compatibility);
    /// prefixed input goes through AES-GCM, returning nil on failure.
    ///
    /// Note: the two live consumer chains (v1 `secretsJson`, v3 `secretBlob`)
    /// carry payloads whose legacy form is the *string itself* (plaintext JSON
    /// / base64-wrapped blob). Inbound they must recover the **original
    /// string**, not decoded bytes, so they use [unsealString] instead. This
    /// method serves the "legacy payload = base64(secret bytes)" shape (e.g.
    /// EnvVarItem.valueB64 if it is ever encrypted) — the generic primitive the
    /// design called for.
    static func unseal(_ s: String) -> Data? {
        if s.hasPrefix(envelopePrefix) {
            return openSealed(String(s.dropFirst(envelopePrefix.count)))
        }
        return Data(base64Encoded: s)
    }

    /// String-payload envelope — the actual entry point for the sync chains:
    ///   - `enc:v1:` prefix → decrypt and restore the original UTF-8 string;
    ///     decryption failure (damaged record, missing/mismatched peer KEK) →
    ///     nil, caller logs and skips the field.
    ///   - no prefix → returned verbatim (legacy plaintext payload, backward
    ///     compatible; never nil on this branch).
    static func unsealString(_ s: String) -> String? {
        guard s.hasPrefix(envelopePrefix) else { return s }
        guard let data = openSealed(String(s.dropFirst(envelopePrefix.count))) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// AES-GCM open on a `combined` buffer; nil on any failure (bad base64,
    /// bad box, authentication failure). Authentication failures are
    /// indistinguishable from "wrong key" here by design — both mean "skip".
    private static func openSealed(_ b64: String) -> Data? {
        guard let combined = Data(base64Encoded: b64),
              let box = try? AES.GCM.SealedBox(combined: combined) else { return nil }
        return try? AES.GCM.open(box, using: kek())
    }

    // MARK: - KEK (Keychain, synchronizable)

    private static func kek() throws -> SymmetricKey {
        if let data = readKEK() {
            guard data.count == 32 else {
                // Refuse to overwrite a wrong-sized item: destroying it would
                // permanently orphan every ciphertext already uploaded.
                logger.error("[SyncSecrets] stored KEK has wrong size (\(data.count)B) — refusing to overwrite")
                throw SyncSecretBoxError.corruptStoredKey
            }
            return SymmetricKey(data: data)
        }
        // Generate a 256-bit KEK and add it. Concurrent first-use window: the
        // add fails with errSecDuplicateItem if another thread (or an arriving
        // sync copy) wrote it first — re-read once and use that.
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw SyncSecretBoxError.randomGenerationFailed
        }
        let data = Data(bytes)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecValueData as String: data,
            // Synchronizable-compatible accessibility (see header note).
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecAttrSynchronizable as String: true,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecSuccess {
            logger.info("[SyncSecrets] generated new sync KEK (32B, synchronizable)")
            return SymmetricKey(data: data)
        }
        if status == errSecDuplicateItem, let existing = readKEK(), existing.count == 32 {
            return SymmetricKey(data: existing)
        }
        logger.error("[SyncSecrets] keychain KEK write failed: status=\(status)")
        throw SyncSecretBoxError.keychainStatus(status)
    }

    /// Read the KEK item. Primary query targets the synchronizable item (the
    /// one iCloud Keychain shares across devices); a non-synchronizable fallback
    /// guards against any future write-shape drift — in practice it never hits.
    private static func readKEK() -> Data? {
        var result: AnyObject?
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrSynchronizable as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data {
            return data
        }
        query[kSecAttrSynchronizable as String] = false
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data {
            return data
        }
        return nil
    }
}