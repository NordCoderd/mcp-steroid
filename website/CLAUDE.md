# MCP Steroid Website

This directory contains the Hugo source for the MCP Steroid website at https://mcp-steroid.jonnyzzz.com

## Requirements

- Docker and Docker Compose (no other system dependencies required)

## Directory Structure

- `hugo.toml` - Hugo configuration with site parameters and featured videos
- `layouts/` - Hugo templates
- `static/` - Static assets (logo, CNAME)
- `content/` - Content files
- `mcp-steroid-public/` - Checkout of the public repository (generated output)

## Development

```bash
# First time setup - clone the public repository
make setup

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

3. **Featured Videos**: Update the `[[params.featuredVideos]]` entries in `hugo.toml` to change which videos appear on the homepage.

4. **CNAME**: The `static/CNAME` file configures the custom domain. Do not remove it.
