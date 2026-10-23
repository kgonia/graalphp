package pl.codeloom.php.builtins;

/**
 * The built-ins' per-context state: pattern caches and preg_last_error(), json_last_error(), the mbstring
 * substitute character. A context owns one; the built-in functions themselves are language-level.
 */
public final class BuiltinState {
    final PcreFunctions pcre = new PcreFunctions();
    final JsonFunctions.State json = new JsonFunctions.State();
    final MbFunctions.State mb = new MbFunctions.State();
}
