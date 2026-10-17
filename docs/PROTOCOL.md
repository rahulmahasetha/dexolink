# MacDroid Protocol (v1)

## Overview
MacDroid uses a custom application-level protocol over a TLS 1.3 encrypted TCP connection. The protocol handles device authentication, file metadata exchange, and chunked file transfer with integrity checks.

## Framing
All messages use a length-prefixed framing format over the TCP stream.

`[4 bytes: Length] [Message Payload]`

- **Length**: A 32-bit big-endian unsigned integer representing the length of the Message Payload in bytes.
- **Message Payload**: The data corresponding to a message. Usually JSON-encoded, except for `FILE_CHUNK` which has a hybrid structure (JSON metadata + raw binary chunk).

## Message Structure

Messages (other than the binary part of `FILE_CHUNK`) are JSON-encoded strings. Each message must contain a `type` field identifying the message type.

### Message Types

#### 1. HELLO
Sent immediately after TLS connection is established by the initiator (sender).

```json
{
  "type": "HELLO",
  "version": 1,
  "deviceId": "sender_device_id_hex",
  "deviceName": "Sender's MacBook"
}
```

#### 2. HELLO_ACK
Response to HELLO. Indicates whether the receiver recognizes the sender's deviceId (paired).

```json
{
  "type": "HELLO_ACK",
  "version": 1,
  "deviceId": "receiver_device_id_hex",
  "deviceName": "Receiver's Android",
  "paired": true
}
```

#### 3. PAIR_REQUEST
Requests pairing if not already paired. Contains the public key for future TLS pinning.

```json
{
  "type": "PAIR_REQUEST",
  "publicKey": "base64_encoded_public_key"
}
```

#### 4. PAIR_RESPONSE
Response to PAIR_REQUEST. `accepted` is true if the user confirmed the pairing PIN/QR.

```json
{
  "type": "PAIR_RESPONSE",
  "accepted": true,
  "publicKey": "base64_encoded_public_key_of_responder"
}
```

#### 5. TRANSFER_REQUEST
Proposes a file transfer.

```json
{
  "type": "TRANSFER_REQUEST",
  "transferId": "uuid-string",
  "totalFiles": 2,
  "totalSize": 10485760
}
```

#### 6. TRANSFER_RESPONSE
Accepts or rejects the transfer.

```json
{
  "type": "TRANSFER_RESPONSE",
  "transferId": "uuid-string",
  "accepted": true
}
```

#### 7. FILE_META
Sends metadata for the next file to be transferred.

```json
{
  "type": "FILE_META",
  "transferId": "uuid-string",
  "fileId": "uuid-string",
  "name": "photo.jpg",
  "size": 5242880,
  "mimeType": "image/jpeg"
}
```

#### 8. FILE_CHUNK
Contains binary data for the file. The payload of `FILE_CHUNK` is structured differently:
`[JSON Length (2 bytes big-endian)] [JSON String] [Binary Chunk]`

JSON String:
```json
{
  "type": "FILE_CHUNK",
  "fileId": "uuid-string",
  "offset": 0
}
```
Binary chunk follows immediately after the JSON string. Max chunk size is 64KB.

#### 9. FILE_COMPLETE
Indicates all chunks for the file have been sent. Contains SHA-256 hash for integrity verification.

```json
{
  "type": "FILE_COMPLETE",
  "fileId": "uuid-string",
  "sha256": "hex_string_hash"
}
```

#### 10. FILE_ACK
Acknowledges receipt and successful verification of the file.

```json
{
  "type": "FILE_ACK",
  "fileId": "uuid-string",
  "verified": true
}
```

#### 11. PROGRESS
Sent periodically to update transfer progress.

```json
{
  "type": "PROGRESS",
  "transferId": "uuid-string",
  "fileId": "uuid-string",
  "bytesReceived": 1024000
}
```

#### 12. CANCEL
Cancels a transfer. Can be sent by either party.

```json
{
  "type": "CANCEL",
  "transferId": "uuid-string"
}
```

#### 13. ERROR
Indicates an error occurred.

```json
{
  "type": "ERROR",
  "code": 2004,
  "message": "Checksum mismatch"
}
```

#### 14. PING / PONG
Keep-alive messages.

```json
{
  "type": "PING"
}
```

```json
{
  "type": "PONG"
}
```

### Universal Clipboard Messages

#### 15. CLIPBOARD_PUSH
Pushes text clipboard content.

```json
{
  "type": "CLIPBOARD_PUSH",
  "sequenceNumber": 12345,
  "content": "copied text"
}
```

#### 16. CLIPBOARD_ACK
Acknowledges receipt of clipboard content to prevent loops.

```json
{
  "type": "CLIPBOARD_ACK",
  "sequenceNumber": 12345
}
```

## Error Codes
| Code | Meaning |
|------|---------|
| 1001 | Unsupported protocol version |
| 1002 | Authentication failed |
| 1003 | Device not paired |
| 2001 | Transfer rejected by user |
| 2002 | Insufficient storage |
| 2003 | File too large |
| 2004 | Checksum mismatch |
| 2005 | Transfer timeout |
| 2006 | Transfer cancelled |
| 3001 | Invalid message format |
| 3002 | Unexpected message type |
| 3003 | Connection lost |
