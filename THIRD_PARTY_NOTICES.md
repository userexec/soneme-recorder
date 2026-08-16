# Third-party notices

Soneme Recorder uses the **LAME MP3 encoder** through a small JNI bridge.

The Android native build fetches the Android-adjusted `libmp3lame` source tree from:

`https://github.com/KosmoSakura/AudioHertzDecoder` at commit `43b07de55d9e9de7edc3ddb2bf3e9464a651fae4`

That project derives its encoder sources from LAME 3.100. LAME is distributed under the GNU Lesser General Public License; see the LAME project/source distribution for the applicable license text and source. Soneme Recorder does not copy LAME metadata into recordings: it disables LAME's automatic ID3 and Xing/Info generation and creates its own ID3v2.4 metadata at final save.

The Soneme Recorder application code is separate from the fetched third-party encoder sources; LAME remains under its own license.
