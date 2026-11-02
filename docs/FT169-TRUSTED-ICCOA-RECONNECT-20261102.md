# FT169 — remembered ICCOA reconnection pilot

Owner requested 2026-11-02: prioritize convenient reconnect without a six-digit
PIN over other ICCOA research. FT169 retains FT168 and earlier source commits.

## Verified current behavior

FT168 always calls requestPin() after GATT services discovery, including for a
remembered car. Its AndroidKeyStore EC identity, stable client id and synthetic
MAC are retained, but no reusable ICCOA session trust-token is decoded from OEM
Carlink.apk; the SignIn HMAC binds the current six-digit PIN and both nonces.

## FT169 owner-controlled experimental solution

On **first use**, pair as usual with the six-digit PIN displayed by the car.
After a successful cryptographic ICCOA handshake and confirmation, encrypt the
accepted PIN under an AES-GCM key generated and held by AndroidKeyStore. Only
ciphertext is saved in private SharedPreferences, bound as authenticated
associated data to the stable phone profile id and owner-selected car name.
The PIN is neither logged nor exported to diagnostic ZIP.

On the **next connection to the remembered car**, after normal BLE scan and
service discovery, try that previously accepted PIN **once**. Bluetooth and
TCP authentication still go through their standard, fully verified ICCOA
handshake. If the car permits reusing this secret, subsequent links require
no user input. A different car never receives the saved PIN.

If the car's PIN is rotated or rejected during BLE bootstrap, clear the cache
and ask for the current six-digit code in the same connection. If the car
rejects after TCP authentication, clear the cache and terminate; user retries
the connection once using the current PIN. No brute force, no PIN guessing,
no suppression of car challenges, no PIN in reports. All failures must be
treated as normal refusal, not an incorrect successful authentication.

Important: **PINless future connections are NOT proven** until owner HU
testing. OEM Carlink may require a fresh code each time despite stable key and
ID; no reusable device-specific trusted token was established in current
source analysis. This pilot determines whether the existing Toyota receiver
accepts the earlier PIN, and falls back safely. Do not label it a complete
solution until field evidence confirms success on the second connection.

## Test

1. Install signed FT169 over FT168; keep existing ICCOA/Bluetooth pairings.
2. First connection: enter code from HU once, establish live projection.
3. Disconnect projection normally, reconnect: check whether FT skips the PIN.
4. If rejected, observe manual fallback and do not keep retrying a stale code.
5. Save a ZIP report (without displaying PINs or exact coordinates).
6. Verify unchanged FT home GPS and Android Auto baseline.

Approval note: no protected master-plan edge or HU owner P0 acceptance advanced.
