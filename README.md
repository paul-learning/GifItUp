# GifItUp

A messenger for friends where you can only send GIFs. Every chat is end-to-end encrypted with [MLS](https://www.rfc-editor.org/rfc/rfc9420) via [OpenMLS](https://github.com/openmls/openmls), and the server never learns which GIF you sent.

- **Clients:** Android + Web (Kotlin Multiplatform, Compose Multiplatform)
- **Backend:** Ktor + PostgreSQL on a single VM
- **Status:** pre-alpha, see [docs/ROADMAP.md](docs/ROADMAP.md)

## Docs
- [Roadmap](docs/ROADMAP.md)
- [Architecture decisions](docs/adr/)
- [Threat model](docs/security/threat-model.md)
- [Contributor / agent working agreement](CLAUDE.md)
