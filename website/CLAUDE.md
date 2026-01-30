# MCP Steroid Website

This directory contains the Hugo source for the MCP Steroid website at https://mcp-steroid.jonnyzzz.com

## Architecture Overview

The website is built from this directory using Hugo and deployed via GitHub Pages.

### What is `mcp-steroid-public/`?

The `mcp-steroid-public/` directory is a **separate clone of the same repository** (`jonnyzzz/mcp-steroid`). This setup allows:

1. **Hugo builds to `mcp-steroid-public/docs/`** - Hugo outputs the static site here
2. **GitHub Pages serves from `docs/` folder** - The main repository's `docs/` folder is served at https://mcp-steroid.jonnyzzz.com
3. **Independent commits** - Website changes can be committed and pushed separately from plugin code changes

This is a common pattern for GitHub Pages deployment where the source (Hugo markdown) and output (HTML) live in different places but the same repository.

**Note:** The `mcp-steroid-public/` directory is ignored in `.gitignore` - it's a local clone used only for building and publishing.

## Requirements

- Docker and Docker Compose (no other system dependencies required)

## Directory Structure

- `hugo.toml` - Hugo configuration with site parameters and featured videos
- `layouts/` - Hugo templates
- `static/` - Static assets (logo, CNAME)
- `content/` - Content files (markdown)
- `mcp-steroid-public/` - Checkout of the public repository (build output destination)

## Quick Start

```bash
# Start development server with live reload at http://localhost:1313
# This will auto-clone mcp-steroid-public if not present
make dev

# Build the site to mcp-steroid-public/docs
make build
```

The Makefile automatically clones the public repository if it doesn't exist.

## Manual Setup (if needed)

```bash
# Explicitly clone the public repository
make setup
```

## Development

```bash
# Start development server with live reload at http://localhost:1313
make dev

# Build the site to mcp-steroid-public/docs
make build
```

## Publishing

After running `make build`, commit and push changes in `mcp-steroid-public/`:

```bash
cd mcp-steroid-public
git add docs README.md
git commit -m "Update website"
git push origin main
```

## Important Notes

1. **Version Sync**: The plugin version is read from `../VERSION` file during build. The Makefile automatically updates `hugo.toml` with the current version before building. Always keep `../VERSION` as the source of truth for the version number.

2. **Public Repository README**: The public repository at https://github.com/jonnyzzz/mcp-steroid contains issues and is user-facing. The README.md in that repository should be kept in sync with the website content and provide similar information about the plugin.

3. **What's New**: Add new entries to `[[params.whatsnew]]` in `hugo.toml` with `date` and `text` fields. Entries are displayed in the order they appear in the file (newest first).

4. **Featured Videos**: Update the `[[params.featuredVideos]]` entries in `hugo.toml` to change which videos appear on the homepage.

5. **CNAME**: The `static/CNAME` file configures the custom domain. Do not remove it.

6. **version.json**: Published at `/version.json` with the current version. Generated automatically during build.

## Workflow Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  Main Repository (jonnyzzz/mcp-steroid)                         │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  website/                                                    ││
│  │  ├── content/           ← Hugo source (markdown)             ││
│  │  ├── layouts/           ← Hugo templates                     ││
│  │  ├── hugo.toml          ← Configuration                      ││
│  │  └── mcp-steroid-public/ ← Clone of main repo (ignored)      ││
│  │      └── docs/          ← Hugo build output                  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                    make build │ (Hugo outputs to docs/)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  GitHub Pages serves from jonnyzzz/mcp-steroid:main/docs/       │
│  → https://mcp-steroid.jonnyzzz.com                             │
└─────────────────────────────────────────────────────────────────┘
```
