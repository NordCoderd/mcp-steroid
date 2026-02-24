
### Pattern 3: State File Manipulation

```bash
# Create/modify plugin state to trigger reload
cat > /path/to/project/.idea/yourPlugin.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="YourPluginService">
    <!-- Your plugin state here -->
  </component>
</project>
EOF
# Target IDE will detect file change and reload
```

---

## Troubleshooting

### Target IDE Won't Start

```bash
# Check port availability
lsof -i :5005  # or whatever debug port

# Kill conflicting process if needed
kill <PID>
```

### Breakpoints Don't Hit

1. Code not executed yet
2. Breakpoint in wrong file/line
3. Source mismatch (rebuild needed)

**Solution:** Add logging or use exception as breakpoint:
