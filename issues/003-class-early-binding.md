# 003: Top-level classes aren't bound early

**State:** Fixed (`517557e`; the compile's visibility of other files' classes `f85d4c9`), no deviation left

**Symptom:** a class used in a file above its own declaration isn't found:
```php
spl_autoload_register(array(new SimplePie_Autoloader(), 'autoload'));  // PHP: works; Truffle PHP: Error
class SimplePie_Autoloader { /* ... */ }
```
WordPress's `wp-includes/SimplePie/autoloader.php` does exactly this. Each dashboard visit's AJAX request for the news widget (`wp_ajax_dashboard_widgets` → `wp_dashboard_primary`) ends in `PHP Fatal error: Uncaught Error: Class "SimplePie_Autoloader" not found`, 202 times in 201 visits in `tests/gate2/memory.sh`'s logs (2026-10-28). The page itself renders, so Gate 2's page checks don't catch it.

**Cause:** `Translator.fileStatements` hoists function declarations into the file's prologue but leaves class declarations in the body, where they run in order. php-src (`zend_compile_class_decl`) binds a top-level class at compile time when it implements no interfaces and uses no traits, and either has no parent or has a parent that is already linked. `FileRootNode`'s comment says classes are hoisted; they aren't.

**Fix:** the classes are bound as php-src with opcache binds them (the reference's CLI has `opcache.enable_cli=1`,
which changes the rules: `ZEND_COMPILE_DELAYED_BINDING`, `ZEND_COMPILE_IGNORE_OTHER_FILES`). `Translator.topLevelClass`
decides at the file's translation, `DeclareNodes.EarlyClassNode` binds in the file's prologue, after the functions:
- A top-level class (in a braced namespace or a `{}` block too) that implements no interface and uses no trait, and
  isn't an enum, is linked by the compile if it has no parent or a parent that is a built-in or a class linked
  earlier in the same file, and its name isn't a built-in's or taken earlier in the file. It is bound when the file
  is loaded and has no statement; a name taken then is `E_ERROR` "Cannot redeclare class X (previously declared in
  ...)" at the class's line before any of the file runs (`zend_accel_class_hash_copy`), as on including the file twice.
- Such a class with another parent is bound at the load, after those, if its name is free and its parent declared
  by then (`zend_accel_do_delayed_early_binding`); its statement then does nothing (`ZEND_DECLARE_CLASS_DELAYED`),
  else declares it as before (autoloading the parent).
- Either binding is skipped when an inheritance check (a method's signature, a redeclared property's or typed
  constant's type) needs a class not declared yet (`zend_can_early_bind`'s `INHERITANCE_UNRESOLVED`); an error
  still binds, to raise it. A class the compile would link that is skipped so is tried again with the delayed ones
  (`ZEND_DECLARE_CLASS_DELAYED`), then left to its statement. The compile's attempt sees only the built-ins and the
  classes of the file being compiled (`class_visible` with `ZEND_COMPILE_IGNORE_OTHER_FILES`): `Variance.canEarlyBind` looks classes up as the file's
  compile does (`PhpContext.compiledFile`), so a check that needs another file's class leaves the class to the
  delayed binding, which skips a name already taken; the statement then raises the redeclaration
  (`class-early-binding-files`, bytecode-only `f85d4c9`).
- Every other class is declared by its statement, as before.

"Cannot redeclare" now names the class already declared (php-src's `old_ce->name`), not the new declaration's
spelling. Tests: `tests/smoke/class-early-binding.php` (with `class-early-binding.inc`, `class-early-binding-ns.inc`),
`class-early-binding-variance.php`.
