# ADR-002: AES-256-GCM for stored target passwords, with a random IV per call

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-001](001-four-maven-modules.md)

## Context

To run `mysqldump` against a target, this tool must present that target's
password. The password therefore cannot be hashed — it has to be recoverable.
It has to be stored, and it has to be stored encrypted.

## Decision

AES-256-GCM. A fresh 12-byte IV is drawn from `SecureRandom` for every
encryption, and the stored value is
`Base64(IV || ciphertext || authentication tag)`.

The key is 32 raw bytes supplied Base64-encoded in `ENCRYPTION_SECRET_KEY`.
It is **not** derived from a passphrase. The application refuses to start if the
variable is missing or does not decode to exactly 32 bytes.

Only the application layer calls `EncryptionPort`.

## Rationale

**Why GCM rather than CBC.** GCM authenticates as well as encrypts, so a
ciphertext altered in the database fails to decrypt instead of yielding
plausible garbage that gets handed to a MySQL client.

**Why a random IV every time, and why it is not a detail.** GCM's security
collapses if an IV is ever reused under the same key. A random 12-byte IV per
call also means two targets sharing a password do not share a ciphertext, so the
table itself reveals nothing. This is the single property most worth a test, and
`AesGcmEncryptionAdapterTest` asserts it directly.

**Why no key derivation.** A KDF over a passphrase would let a short,
guessable secret masquerade as a 256-bit key. Demanding 32 real bytes makes the
strength of the key visible at the point it is configured.

**Why no default key.** A default would mean every deployment that forgot to set
the variable protected its passwords with a secret published in this repository.
Failing to start is the honest outcome.

## Consequences

- Losing `ENCRYPTION_SECRET_KEY` means every stored password is unrecoverable
  and every target must be re-registered. There is no recovery path and no key
  rotation mechanism; rotation is a change to design when it is needed, not a
  hook to leave dangling now.
- Ciphertext is not deterministic, so a stored password cannot be searched or
  compared by equality. Nothing needs to.
- `DatabaseTarget.passwordCiphertext` holds ciphertext at every point in its
  life. When a slice needs the plaintext, it belongs in a separate short-lived
  type named for what it carries — never assigned back into a field named for
  ciphertext.
