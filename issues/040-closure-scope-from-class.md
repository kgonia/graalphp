# 040: `Closure::call`, `bind` and `bindTo` don't change the scope of a closure made in a class

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. 88 tests fail on it:
- `Tests_HtmlApi_WpHtmlTagProcessorScriptTag` (60): `Call to private method
  WP_HTML_Tag_Processor::get_script_content_type() from scope Tests_HtmlApi_WpHtmlTagProcessorScriptTag`;
- `WP_REST_Global_Styles_Controller_CSS_Validation_Test` (28): the same for a protected method.

**Symptom:** a closure created at the top level takes its new scope. One created inside a class method keeps that
class as the scope of its method calls:
```php
class Target { private function secret() { return 'secret'; } protected function prot($x) { return "prot $x"; } }
class Caller {
    static function viaCall(Target $t) { $g = function () { return $this->secret(); }; return $g->call($t); }
    function viaBind(Target $t) { $v = Closure::bind(function ($x) { return $this->prot($x); }, $t, $t); return $v('a'); }
    function viaBindTo(Target $t) { $v = (function () { return $this->secret(); })->bindTo($t, Target::class); return $v(); }
}
// PHP 8.5: "secret", "prot a", "secret"
// Truffle PHP: Error: Call to private method Target::secret() from scope Caller (and the same for the other two)
```

**Cause:** not diagnosed. It is likely the visibility check that gate-2.md describes: "a site in a method takes
its scope from its code, not the frame". A closure's body is code inside `Caller`, so a rebound closure is still
checked against `Caller`. Property reads may have the same problem.

**Fix:** to do. Inside a closure, take the scope from the closure's bound scope, not from the class whose code holds
it. Code that isn't a closure keeps its fast path.
