# Third-party notices

Whisper LP (Phone Whisper Android) is licensed under the Apache License, Version 2.0: see
[LICENSE](LICENSE) and [NOTICE](NOTICE). The APK also contains the third-party software listed
here. Each keeps its own license. Where a license asks for its text to travel with the binary, the
text is below or in the [`licenses/`](licenses) folder, and all of it is also inside the app under
**Settings > About > Open-source licenses**.

Several components below use the Apache License, Version 2.0. Its full text is the same as
[LICENSE](LICENSE) and is not repeated for each one.

Contents:

1. [Wake word native library (sherpa-onnx)](#1-wake-word-native-library-sherpa-onnx)
2. [Wake word model](#2-wake-word-model)
3. [Java and Kotlin libraries](#3-java-and-kotlin-libraries)
4. [Upstream project](#4-upstream-project)
5. [License texts](#5-license-texts)

## 1. Wake word native library (sherpa-onnx)

The wake word runs on `libsherpa-onnx-jni.so` (arm64-v8a), taken unmodified from the sherpa-onnx
v1.13.8 release asset `sherpa-onnx-v1.13.8-android-static-link-onnxruntime.tar.bz2` by
`scripts/fetch_sherpa.sh`. sherpa-onnx's Kotlin bindings from `sherpa-onnx/kotlin-api/` at the same tag are vendored,
unmodified, under `app/src/main/kotlin/com/k2fsa/sherpa/onnx/`.

Upstream builds that one library with everything its own build script enables
(`build-android-arm64-v8a.sh` at v1.13.8), so it statically contains the components below. Whisper
LP only calls keyword spotting. The text-to-speech and speaker-diarization parts (espeak-ng,
piper-phonemize, hclust-cpp) are in the file but are never called.

| Component | Version | License | Source |
|---|---|---|---|
| sherpa-onnx (library and Kotlin bindings) | v1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| ONNX Runtime (statically linked) | 1.28.2 | MIT, plus its own third-party notices | https://github.com/microsoft/onnxruntime/tree/v1.28.2 |
| kaldi-native-fbank | v1.22.3 | Apache-2.0 | https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.3 |
| kaldi-decoder | v0.3.0 | Apache-2.0 | https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0 |
| kaldifst (via kaldi-decoder) | v1.8.0 | Apache-2.0 | https://github.com/k2-fsa/kaldifst/tree/v1.8.0 |
| OpenFst (csukuangfj fork) | v1.8.5-2026-07-09 | Apache-2.0 | https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09 |
| simple-sentencepiece | v0.7 | Apache-2.0 | https://github.com/pkufool/simple-sentencepiece/tree/v0.7 |
| Eigen (header-only, via kaldi-decoder) | 3.4.0 | MPL-2.0 | https://gitlab.com/libeigen/eigen/-/tree/3.4.0 |
| espeak-ng (csukuangfj fork) | commit ed530aa | GPL-3.0-or-later (one file BSD-2-Clause; Unicode data files under the Unicode license) | https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1 |
| piper-phonemize (csukuangfj fork) | commit f3ff95a | MIT | https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4 |
| JSON for Modern C++ (nlohmann/json) | v3.12.0 | MIT | https://github.com/nlohmann/json/tree/v3.12.0 |
| hclust-cpp | 2026-02-25 | BSD-2-Clause | https://github.com/csukuangfj/hclust-cpp/tree/2026-02-25 |
| LLVM libc++ (Android NDK, static) | as shipped with the NDK upstream used | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

The versions are the ones pinned by sherpa-onnx v1.13.8 (its `cmake/` folder and build script).
ONNX Runtime's version is also embedded in the library itself.

**About espeak-ng and the GPL.** espeak-ng is licensed under the GNU General Public License,
version 3 or later. Its full text is in [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt). The
complete corresponding source for `libsherpa-onnx-jni.so` is public: the sherpa-onnx v1.13.8
source tree and build script linked above, and the source of every component in the table at the
exact version or commit linked. The source of Whisper LP itself is this repository.

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

### espeak-ng: BSD-2-Clause part (`getopt.c`)

```text
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### espeak-ng: Unicode data files

```text
Unicode Data Files include all data files under the directories
http://www.unicode.org/Public/, http://www.unicode.org/reports/,
http://www.unicode.org/cldr/data/, http://source.icu-project.org/repos/icu/, and
http://www.unicode.org/utility/trac/browser/.

Unicode Data Files do not include PDF online code charts under the
directory http://www.unicode.org/Public/.

Software includes any source code published in the Unicode Standard
or under the directories
http://www.unicode.org/Public/, http://www.unicode.org/reports/,
http://www.unicode.org/cldr/data/, http://source.icu-project.org/repos/icu/, and
http://www.unicode.org/utility/trac/browser/.

NOTICE TO USER: Carefully read the following legal agreement.
BY DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING UNICODE INC.'S
DATA FILES ("DATA FILES"), AND/OR SOFTWARE ("SOFTWARE"),
YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT.
IF YOU DO NOT AGREE, DO NOT DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE
THE DATA FILES OR SOFTWARE.

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2018 Unicode, Inc. All rights reserved.
Distributed under the Terms of Use in http://www.unicode.org/copyright.html.

Permission is hereby granted, free of charge, to any person obtaining
a copy of the Unicode data files and any associated documentation
(the "Data Files") or Unicode software and any associated documentation
(the "Software") to deal in the Data Files or Software
without restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, and/or sell copies of
the Data Files or Software, and to permit persons to whom the Data Files
or Software are furnished to do so, provided that either
(a) this copyright and permission notice appear with all copies
of the Data Files or Software, or
(b) this copyright and permission notice appear in associated
Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT OF THIRD PARTY RIGHTS.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS
NOTICE BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL
DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THE DATA FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder
shall not be used in advertising or otherwise to promote the sale,
use or other dealings in these Data Files or Software without prior
written authorization of the copyright holder.
```

### piper-phonemize

```text
MIT License

Copyright (c) 2023 Michael Hansen

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

### hclust-cpp

```text
Copyright:
  * fastcluster_dm.cpp & fastcluster_R_dm.cpp:
     © 2011 Daniel Müllner <http://danifold.net>
  * fastcluster.(h|cpp) & demo.cpp & plotresult.r:
     © 2018 Christoph Dalitz <http://www.hsnr.de/ipattern/>
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

  * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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

The keyword list, `app/src/main/assets/wake/keywords.txt`, is part of Whisper LP.

## 3. Java and Kotlin libraries

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

## 4. Upstream project

Whisper LP is a modified version of Phone Whisper by kafkasl
(https://github.com/kafkasl/phone-whisper), licensed under the Apache License, Version 2.0. See
[NOTICE](NOTICE).

## 5. License texts

| File | Used by |
|---|---|
| [LICENSE](LICENSE) | Apache License 2.0: Whisper LP, sherpa-onnx, kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece, the wake word model, and the Java and Kotlin libraries |
| [`licenses/onnxruntime-ThirdPartyNotices.txt`](licenses/onnxruntime-ThirdPartyNotices.txt) | ONNX Runtime's third-party notices |
| [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) | espeak-ng |
| [`licenses/MPL-2.0.txt`](licenses/MPL-2.0.txt) | Eigen, the Public Suffix List in OkHttp |
| [`licenses/kaldifst-LICENSE.txt`](licenses/kaldifst-LICENSE.txt) | kaldifst |
