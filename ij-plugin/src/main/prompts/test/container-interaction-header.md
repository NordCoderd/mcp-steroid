Test: Interact with Running Docker Test Container

Use this when an integration test is running (or paused) inside Docker and you need
to debug it visually — take screenshots, send keyboard/mouse input, or call the
test container's MCP Steroid HTTP endpoint.

Works via TestSessionHandle, which reads session-info.txt written by the test factory.
It uses docker exec + xdotool for xcvb input and scrot for screenshots.

Prerequisites:
- Docker available on the host machine
- At least one integration test session currently running (or recently run)

Output: Screenshot path, interaction confirmation, MCP HTTP response
