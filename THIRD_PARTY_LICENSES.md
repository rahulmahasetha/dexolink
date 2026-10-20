# Third-Party Open Source Licenses

DexoLink incorporates or adapts code and concepts from the following open-source projects under their respective licenses:

---

## p2p.kiwi

- **Project:** p2p.kiwi (https://github.com/mistweaverco/p2p.kiwi)
- **License:** MIT License
- **Copyright:**
  - Copyright (c) 2026+ The Don't Be Evil Company
  - Copyright (c) 2024-2025 mistweaverco
- **Adapted Components:**
  - Screen sharing display pixel dimension calculation (`displayPixelSize`)
  - Share surface frame and display matching (`shareSurfaceFromFrame`, `frameMatchesDisplay`)
  - Multi-display source enumeration and capture frame readiness verification
  - Dynamic aspect ratio and DPI resolution scaling across platforms (Android, macOS, Windows, iOS)

### MIT License Text

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## webrtc-android (GetStream)

- **Project:** webrtc-android (https://github.com/GetStream/webrtc-android)
- **License:** Apache License 2.0
- **Copyright:**
  - Copyright (c) 2014-2023 Stream.io Inc. All rights reserved.
  - Copyright (c) 2011 The WebRTC project authors. All rights reserved.
- **Adapted Components:**
  - Camera enumeration & lens facing detection (`Camera2Enumerator`, `isFrontFacing`, `isBackFacing`, `LENS_FACING_FRONT`, `LENS_FACING_BACK`)
  - Supported resolution format negotiation (`CaptureFormat`, supported format matching)
  - Display and video aspect ratio layout measurement (`RendererCommon`, `VideoLayoutMeasure`, `getLayoutMatrix`, `ScalingType`)
  - Front camera horizontal mirroring and sensor rotation degree management (0, 90, 180, 270 degrees)
  - WebRTC signaling protocol structures (SDP offer/answer negotiation and ICE candidate exchange)
  - Seamless front/back camera lens toggling during active video streams

### Apache License 2.0 Text

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

