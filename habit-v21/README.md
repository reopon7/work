# Habit Browser V21 Java compatibility layer

This branch is used only to compile a real Java source file into Android DEX.

- `Mod.java` is compiled by `javac`.
- The resulting `.class` is converted by Android `dx` to `classes2.dex`.
- The compile-time `App.java` is a stub and is NOT included in `classes2.dex`.
- The runtime superclass `App` continues to come from the V17 legacy `classes.dex`.

No V17 method bytecode is patched by this build.

This is the first migration layer toward a fully reconstructed Java project; it is
not a claim that all 3,000+ legacy classes have already been restored to original
Java source.
