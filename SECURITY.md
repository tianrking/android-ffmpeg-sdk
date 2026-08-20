# Security policy

## Supported line

Only the latest published SDK minor and its pinned native runtime receive security fixes. The
current development baseline is FFmpeg 8.1.2; FFmpeg 9.x is not yet a validated production line.

## Threat model

Media input can be hostile. Treat container metadata, codecs, subtitles, fonts, filter arguments,
network responses, and content providers as untrusted. Native decoder bugs can corrupt the host
application process.

The current engine therefore disables network input by default, avoids shell parsing, pins allowed
FFmpeg majors, and makes runtime provenance an application responsibility. It is not yet process
isolated; do not use the snapshot to process adversarial media in a high-value application.

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
