This folder is populated by GitHub Actions at build time.

Perspective USB Bridge does not commit third-party driver binaries to source control.
The release workflow downloads the current signed x64 usbip-win2 runtime from the
OSSign release repository, records its SHA256 hash, and bundles its licence and
build metadata with the Windows application.
