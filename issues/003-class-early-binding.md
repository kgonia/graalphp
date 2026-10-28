# 003: Top-level classes aren't bound early

**State:** Open

**Symptom:** a class used in a file above its own declaration isn't found:
```php
spl_autoload_register(array(new SimplePie_Autoloader(), 'autoload'));  // PHP: works; Truffle PHP: Error
class SimplePie_Autoloader { /* ... */ }
```
WordPress's `wp-includes/SimplePie/autoloader.php` does exactly this. Each dashboard visit's AJAX request for the news widget (`wp_ajax_dashboard_widgets` → `wp_dashboard_primary`) ends in `PHP Fatal error: Uncaught Error: Class "SimplePie_Autoloader" not found`, 202 times in 201 visits in `tests/gate2/memory.sh`'s logs (2026-10-28). The page itself renders, so Gate 2's page checks don't catch it.

**Cause:** `Translator.fileStatements` hoists function declarations into the file's prologue but leaves class declarations in the body, where they run in order. php-src (`zend_compile_class_decl`) binds a top-level class at compile time when it implements no interfaces and uses no traits, and either has no parent or has a parent that is already linked. `FileRootNode`'s comment says classes are hoisted; they aren't.

**Fix:** to do: bind those classes in the prologue, under php-src's conditions (a class with a parent that isn't declared yet stays in place, as do conditional declarations), with a smoke test.
