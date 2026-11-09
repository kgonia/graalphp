<?php
$r = [];
try { $r[] = $obj->hidden(); } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = $obj->priv; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = A2::PRIV; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = A2::$ps; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = A2::sh(); } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = self::class; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = static::class; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = get_class(new self); } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = isset($obj->priv) ? 'set' : 'unset'; } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = (function () { return get_called_class(); })(); } catch (Error $e) { $r[] = $e->getMessage(); }
try { $r[] = is_callable([$obj, 'hidden']) ? 'callable' : 'not'; } catch (Error $e) { $r[] = $e->getMessage(); }
return implode(' | ', $r);
