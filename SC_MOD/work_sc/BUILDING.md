# Building CraftMate (shadow / fat jar)

## What to run

Use this command in the project folder:

```bat
gradlew.bat clean build
```

## Which JAR to use

After build finishes, look in:

`build\\libs\\`

You should see 2 jars:

- `craftmate-<version>.jar`  ✅ **THIS is the one you ship / put into .minecraft/mods**
  - It is a **shadow (fat) jar** with relocated dependencies and reobfuscated for Forge.
- `craftmate-<version>-dev.jar`  (dev-only)
  - For IDE/dev usage. **Do not ship this**.

## Why the jar size matters

If the jar looks "too small", you probably copied the `-dev.jar` by mistake.
The shipped jar must include the embedded audio decoder dependency.
