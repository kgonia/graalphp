<?php
// A callable the runtime calls later (an autoloader, a shutdown function) is resolved once, when it is registered:
// php-src keeps its zend_fcall_info_cache, so a private method the registering scope may call stays callable from
// wherever the autoload or the shutdown happens. Elementor registers its private static Autoloader::autoload this way.

class Loader {
    public static function register() {
        spl_autoload_register([__CLASS__, 'autoload']);
    }
    public static function unregister() {
        return spl_autoload_unregister([__CLASS__, 'autoload']);
    }
    private static function autoload($name) {
        echo "Loader::autoload($name)\n";
        if ($name === 'FromGlobal' || $name === 'FromOther' || $name === 'FromString') {
            eval("class $name {}");
        }
    }
}

class InstanceLoader {
    private $prefix = 'Inst';
    public function register() {
        spl_autoload_register([$this, 'load']);
    }
    public function unregister() {
        return spl_autoload_unregister([$this, 'load']);
    }
    private function load($name) {
        echo "InstanceLoader::load($name)\n";
        if (str_starts_with($name, $this->prefix)) {
            eval("class $name {}");
        }
    }
}

class StringLoader {
    public static function register() {
        spl_autoload_register('StringLoader::find');
    }
    private static function find($name) {
        echo "StringLoader::find($name)\n";
    }
}

class Other {
    public function make() {
        return new FromOther();
    }
}

Loader::register();
$instance = new InstanceLoader;
$instance->register();
StringLoader::register();

var_dump(get_class(new FromGlobal()));
var_dump(get_class((new Other)->make()));
var_dump(class_exists('InstViaClassExists'));
var_dump(class_exists('Missing'));
echo count(spl_autoload_functions()), " autoloaders\n";
// unregistering checks the callable as registering does: a private method only from its class
try {
    spl_autoload_unregister(['Loader', 'autoload']);
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump($instance->unregister());
var_dump(Loader::unregister());
var_dump(Loader::unregister());
echo count(spl_autoload_functions()), " autoloaders\n";
var_dump(class_exists('StillMissing'));

// registering a private method from outside its class is still a TypeError
try {
    spl_autoload_register(['Loader', 'autoload']);
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class Shutdown {
    public function register() {
        register_shutdown_function([$this, 'down'], 'instance');
        register_shutdown_function([__CLASS__, 'staticDown'], 'static');
    }
    private function down($what) {
        echo "Shutdown::down($what)\n";
    }
    private static function staticDown($what) {
        echo "Shutdown::staticDown($what)\n";
    }
}
(new Shutdown)->register();

try {
    register_shutdown_function([new Shutdown, 'down']);
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
echo "end of script\n";
