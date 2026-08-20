# Third-party decompiler engines

Plugin Reconstructor Ultra itself builds only with the JDK and does not bundle third-party decompiler binaries in its main JAR.

The automatic installer downloads official releases over HTTPS:

- Vineflower 1.12.0 — Apache License 2.0;
- CFR 0.152 — MIT License;
- Procyon 0.6.0 — Apache License 2.0.

Downloaded files remain separate in `~/.plugin-reconstructor-ultra/tools`. The installer checks that each file is a valid JAR, verifies the expected engine class, rejects unexpectedly small downloads, and displays SHA-256 after installation. Each engine remains governed by its own license.
