# Third-party notices

Voice Relay is licensed under the Apache License, Version 2.0: see
[LICENSE](LICENSE) and [NOTICE](NOTICE). The APK also contains the third-party software listed
here. Each keeps its own license. Where a license asks for its text to travel with the binary, the
text is below or in the [`licenses/`](licenses) folder, and all of it is also inside the app under
**Settings > About > Open-source licenses**.

Several components below use the Apache License, Version 2.0. Its full text is the same as
[LICENSE](LICENSE) and is not repeated for each one.

Contents:

1. [Wake word native library (sherpa-onnx)](#1-wake-word-native-library-sherpa-onnx)
2. [Wake word model](#2-wake-word-model)
3. [Wake word pronunciation dictionary (CMUdict)](#3-wake-word-pronunciation-dictionary-cmudict)
4. [Java and Kotlin libraries](#4-java-and-kotlin-libraries)
5. [Upstream project](#5-upstream-project)
6. [License texts](#6-license-texts)

## 1. Wake word native library (sherpa-onnx)

The wake word runs on `libsherpa-onnx-jni.so` (arm64-v8a), which this repository's CI builds from
the sherpa-onnx v1.13.8 source (commit `11afbd0`) with `scripts/build_sherpa.sh`. That script runs
upstream's own `build-android-arm64-v8a.sh`, unmodified, with text-to-speech, speaker diarization,
the command-line programs and the C API turned off, links the prebuilt static ONNX Runtime 1.28.2
library that sherpa-onnx v1.13.8 pins, and compiles with Android NDK r28c (28.2.13676358).
sherpa-onnx's Kotlin bindings from `sherpa-onnx/kotlin-api/` at the same tag are vendored,
unmodified, under `app/src/main/kotlin/com/k2fsa/sherpa/onnx/`.

Because text-to-speech and speaker diarization are off, the library contains no espeak-ng,
piper-phonemize, cppjieba or hclust-cpp code, and no GPL-licensed code; CI checks this on every
build (`scripts/verify_sherpa_lib.sh`). It statically contains the components below. Voice Relay
only calls keyword spotting.

| Component | Version | License | Source |
|---|---|---|---|
| sherpa-onnx (library and Kotlin bindings) | v1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| ONNX Runtime (statically linked) | 1.28.2 | MIT, plus its own third-party notices | https://github.com/microsoft/onnxruntime/tree/v1.28.2 |
| kaldi-native-fbank | v1.22.3 | Apache-2.0 | https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.3 |
| kaldi-decoder | v0.3.0 | Apache-2.0 | https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0 |
| kaldifst (via kaldi-decoder) | v1.8.0 | Apache-2.0 | https://github.com/k2-fsa/kaldifst/tree/v1.8.0 |
| OpenFst (csukuangfj fork) | v1.8.5-2026-07-09 | Apache-2.0 | https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09 |
| simple-sentencepiece | v0.7 | Apache-2.0 | https://github.com/pkufool/simple-sentencepiece/tree/v0.7 |
| Eigen (header-only) | 5.0.1 | MPL-2.0 (a few headers carry BSD-3-Clause or Apache-2.0 third-party code; see its `COPYING.README`) | https://gitlab.com/libeigen/eigen/-/tree/5.0.1 |
| KISS FFT (via kaldi-native-fbank) | commit febd4ca | BSD-3-Clause | https://github.com/mborgerding/kissfft/tree/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec |
| JSON for Modern C++ (nlohmann/json) | v3.12.0 | MIT | https://github.com/nlohmann/json/tree/v3.12.0 |
| LLVM libc++ (Android NDK, static) | as shipped with NDK r28c | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

The versions are the ones pinned by sherpa-onnx v1.13.8 and its dependencies' `cmake/` folders, as
downloaded by the CI build. ONNX Runtime's version is also embedded in the library itself. The
complete source of the library is the sherpa-onnx tree and the components linked above, plus
`scripts/build_sherpa.sh` in this repository.

### ONNX Runtime

ONNX Runtime ships its own list of the third-party code it contains. That file is reproduced
unmodified in
[`licenses/onnxruntime-ThirdPartyNotices.txt`](licenses/onnxruntime-ThirdPartyNotices.txt).

```text
MIT License

Copyright (c) Microsoft Corporation

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
```

### OpenFst

```text
Licensed under the Apache License, Version 2.0 (the "License");
you may not use these files except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Copyright 2005-2026 Google LLC.
```

kaldifst's license file adds a note on copyright ownership in front of the Apache License text; it
is reproduced in [`licenses/kaldifst-LICENSE.txt`](licenses/kaldifst-LICENSE.txt).

### Eigen

Eigen is licensed under the Mozilla Public License, version 2.0, reproduced in
[`licenses/MPL-2.0.txt`](licenses/MPL-2.0.txt). Its source code is available at the link in the
table above.

### KISS FFT

```text
Copyright (c) 2003-2010 Mark Borgerding . All rights reserved.

KISS FFT is provided under:

  SPDX-License-Identifier: BSD-3-Clause

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### JSON for Modern C++

```text
MIT License 

Copyright (c) 2013-2025 Niels Lohmann

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
```

## 2. Wake word model

The wake word uses the keyword-spotting model
**sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20**, downloaded by `scripts/fetch_sherpa.sh` from
the sherpa-onnx `kws-models` release
(https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models). The app bundles four of its
files, renamed but otherwise unmodified: the int8 encoder and joiner, the decoder, and
`tokens.txt`.

- Model: a Zipformer transducer trained with icefall (https://github.com/k2-fsa/icefall),
  published by pkufool as `pkufool/icefall-kws-zipformer-zh-en-3M-2025-12-20` on ModelScope
  (https://modelscope.cn/models/pkufool/icefall-kws-zipformer-zh-en-3M-2025-12-20).
- License: Apache License, Version 2.0, as declared on that model card.
- Note: a request to the sherpa-onnx maintainers to confirm the license of the pretrained keyword
  spotting models is open at https://github.com/k2-fsa/sherpa-onnx/issues/3802. This file will
  be updated if the answer changes anything.

The keyword lists are part of Voice Relay: the default "hey claude" lines in `WakeWord.kt`, and
the lines the app builds on the phone from a phrase you type (see the next section).
`app/src/main/assets/wake/empty_keywords.txt` is a blank placeholder.

## 3. Wake word pronunciation dictionary (CMUdict)

To turn a wake phrase you type into the model's phones, the app bundles the
**CMU Pronouncing Dictionary** as `app/src/main/assets/wake/cmudict.txt.gzip`, built by
`scripts/build_cmudict.py` from `cmudict.dict` at commit `74790861f652` of
https://github.com/cmusphinx/cmudict. The script drops the `(2)`-style markers on alternate
pronunciations and the trailing `#` comments, adds a one-line header naming the source, and
gzips the result; every word and pronunciation is otherwise kept as published.

- Copyright (C) 1993-2015 Carnegie Mellon University. All rights reserved.
- License: BSD 2-Clause. Its full text, as published with the dictionary, is in
  [`licenses/cmudict-LICENSE.txt`](licenses/cmudict-LICENSE.txt).

## 4. Java and Kotlin libraries

All of these are licensed under the Apache License, Version 2.0 (full text in
[LICENSE](LICENSE)). Versions are the ones resolved for the release build.

| Library | Version | Copyright | Source |
|---|---|---|---|
| OkHttp | 4.12.0 | Square, Inc. | https://github.com/square/okhttp |
| Okio | 3.6.0 | Square, Inc. | https://github.com/square/okio |
| Kotlin standard library | 2.0.21 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.9.0 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/Kotlin/kotlinx.coroutines |
| JetBrains Java Annotations | 23.0.0 | JetBrains s.r.o. | https://github.com/JetBrains/java-annotations |
| AndroidX (core, appcompat, activity, fragment, lifecycle, recyclerview, emoji2, and the other androidx artifacts they depend on) | core 1.13.1, appcompat 1.7.0, lifecycle 2.8.7 | The Android Open Source Project | https://github.com/androidx/androidx |
| Material Components for Android | 1.12.0 | Google LLC | https://github.com/material-components/material-components-android |
| Guava ListenableFuture | 1.0 | The Guava Authors | https://github.com/google/guava |

OkHttp contains a copy of the Public Suffix List (https://publicsuffix.org/), which is licensed
under the Mozilla Public License, version 2.0 ([`licenses/MPL-2.0.txt`](licenses/MPL-2.0.txt)).

## 5. Upstream project

Voice Relay is a modified version of Phone Whisper by kafkasl
(https://github.com/kafkasl/phone-whisper), licensed under the Apache License, Version 2.0. See
[NOTICE](NOTICE).

## 6. License texts

| File | Used by |
|---|---|
| [LICENSE](LICENSE) | Apache License 2.0: Voice Relay, sherpa-onnx, kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece, the wake word model, and the Java and Kotlin libraries |
| [`licenses/onnxruntime-ThirdPartyNotices.txt`](licenses/onnxruntime-ThirdPartyNotices.txt) | ONNX Runtime's third-party notices |
| [`licenses/MPL-2.0.txt`](licenses/MPL-2.0.txt) | Eigen, the Public Suffix List in OkHttp |
| [`licenses/kaldifst-LICENSE.txt`](licenses/kaldifst-LICENSE.txt) | kaldifst |
| [`licenses/cmudict-LICENSE.txt`](licenses/cmudict-LICENSE.txt) | The CMU Pronouncing Dictionary |
