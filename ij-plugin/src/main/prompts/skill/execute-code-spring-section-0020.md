
---

## CRITICAL: Derive Package Names from TEST IMPORT STATEMENTS — Not from Gradle Group ID

The Gradle `group = 'shop.microservices.api'` in `build.gradle` is a Maven artifact coordinate, NOT the Java package prefix. These often differ! Always read actual test imports first:
