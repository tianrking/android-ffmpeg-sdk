# Distribution and compliance

This is an engineering checklist, not legal advice.

## Runtime profiles

Publish LGPL and GPL native runtimes as separate immutable artifacts. Never make GPL enablement a
hidden build flag on the same coordinate.

For every runtime artifact retain:

- upstream FFmpeg commit/tag and signed release archive;
- verified source archive/signature and hashes;
- complete configure arguments and environment;
- all patches as separate files plus a generated diff;
- Android SDK, NDK, compiler, and linker versions;
- external library source revisions, licenses, and hashes;
- ABI list, exported component list, AAR/APK hashes, and SBOM;
- ELF page alignment, RELRO, and APK ZIP alignment reports;
- exact corresponding source bundle and written source offer text.

Start from `runtime-build-manifest.template.json` in this directory.

## LGPL checklist

The FFmpeg project's own checklist should be reviewed for every release. At minimum:

1. Do not use `--enable-gpl` or `--enable-nonfree` in an LGPL profile.
2. Dynamically link the FFmpeg libraries.
3. Ship or offer the exact corresponding source, including modifications and build scripts.
4. Include LGPL text, FFmpeg attribution, build configuration, and notices inside the app and
   distribution bundle.
5. Do not prohibit reverse engineering needed to debug modifications to the LGPL library.
6. Audit every optional library independently.

Source: [FFmpeg legal and compliance checklist](https://ffmpeg.org/legal.html).

## GPL profile

`libx264` and `libx265` are treated as GPL-only encoder choices by the planner. A GPL runtime has
application-distribution consequences beyond this repository; obtain qualified legal review before
shipping it. Keep it out of the LGPL artifact, POM, demo flavor, and dependency graph.

## Patents and trademarks

Open-source copyright permission is not a codec patent license. Record the codecs, countries,
commercial model, encoding/decoding direction, and expected distribution volume for counsel.

Do not use the FFmpeg logo. State that the SDK is independent and not endorsed by FFmpeg. Review
the final product name before publication to avoid implying official status.

## Third-party binary intake rule (not used by `core-lgpl`)

The official-source core does not ingest a third-party FFmpeg AAR. If a future optional profile
ever proposes one, it is evaluation-only until all of these match:

- its POM/repository points to available source;
- source tag and binary build configuration are identified;
- artifact checksum is pinned;
- all `.so` ABIs and dependencies match the declaration;
- license metadata is internally consistent;
- 16 KB ELF/ZIP alignment and RELRO checks pass;
- the golden media corpus passes on the supported device matrix.
