# Security policy

## Supported line

Only the latest published SDK minor and its pinned native runtime receive security fixes. The
current development baseline is signed FFmpeg 9.0.1 (`n9.0.1`) built with NDK r29.

## Threat model

Media input can be hostile. Treat container metadata, codecs, subtitles, fonts, filter arguments,
network responses, and content providers as untrusted. Native decoder bugs can corrupt the host
application process.

The current engine therefore uses typed argument arrays instead of shell parsing, pins allowed
FFmpeg majors, verifies native provenance, and bounds staging/probe/log resources. Network input
requires both job and runtime opt-in; the enabled mode stages
bounded HTTPS responses after scheme, host, redirect, timeout, and non-public-address checks.
The locked native profile disables FFmpeg networking entirely.

Outputs are encoded to staging storage and committed only after native success. Filesystem commit is
same-directory, but Android content providers do not expose a universal atomic-replace primitive.
The current adapter is also not process isolated. Do not use this snapshot to process adversarial
media in a high-value application until the provider matrix and isolated-worker release gates pass.

## Reporting

Do not open a public issue for a suspected vulnerability. Send a private report to the security
contact configured by the eventual repository owner and include affected versions, reproduction,
impact, and any proposed patch. A real contact must replace this paragraph before public release.

## Update response

When FFmpeg or a bundled external library publishes a relevant fix:

1. rebuild the exact native profile from verified source;
2. run the golden corpus, malformed-input corpus, 16 KB checks, and device smoke matrix;
3. publish new hashes, source bundle, SBOM, and advisory mapping;
4. revoke or clearly mark the affected runtime coordinates.
