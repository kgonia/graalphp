<?php
// Protected and private properties: accessible from the scopes php-src allows; from others, the magic methods if
// the class has them (WP_Block_Type's private $variations behind __get/__isset), else an Error.

class Block {
    private $variations = null;
    protected $context = ['a'];
    public $name = 'block';
    private $secret = 's';

    public function __get($name) {
        echo "__get($name)\n";
        return $name === 'variations' ? ['v1'] : null;
    }

    public function __isset($name) {
        echo "__isset($name)\n";
        return in_array($name, ['variations', 'context'], true);
    }

    public function __set($name, $value) {
        echo "__set($name)\n";
    }

    public function __unset($name) {
        echo "__unset($name)\n";
    }

    public function inside() {
        return [isset($this->variations), $this->secret, $this->context, $this->variations ?? 'null inside'];
    }

    public function closure() {
        return function () {
            return $this->secret;
        };
    }
}

$b = new Block;
var_dump(isset($b->variations), $b->variations, $b->variations ?? 'default', isset($b->secret), empty($b->context));
var_dump(isset($b->name), $b->name);
$b->secret = 'changed';
unset($b->context);
var_dump($b->inside());
var_dump(($b->closure())());

class Plain {
    private $hidden = 1;
    protected $guarded = 2;
}
class Child extends Plain {
    public function guarded() {
        return $this->guarded;
    }
}
class Sibling extends Plain {
    public function peek(Plain $other) {
        return $other->guarded;
    }
}
var_dump((new Child)->guarded(), (new Sibling)->peek(new Child));
foreach ([fn() => (new Plain)->hidden, fn() => (new Plain)->guarded, function () {
    $p = new Plain;
    $p->hidden = 5;
}, function () {
    $p = new Plain;
    unset($p->guarded);
}, fn() => (new Exception('m'))->message, fn() => (new Exception('m'))->previous] as $f) {
    try {
        var_dump($f());
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
$p = new Plain;
var_dump(isset($p->hidden), isset($p->guarded), $p->hidden ?? 'unset to outsiders');

class Template {
    private $title = 'from a template';

    public function render() {
        return include __DIR__ . '/property-visibility.inc';
    }
}
var_dump((new Template)->render());

class MyException extends Exception {
    public function describe() {
        return $this->message . ' at line ' . $this->line;
    }
}
var_dump((new MyException('mine'))->describe());

// a private property declared again by a subclass: the parent's code reaches its own
class Controller {
    private $parent_type;

    public function __construct($type) {
        $this->parent_type = $type;
    }

    public function type() {
        return $this->parent_type;
    }
}
class TemplateController extends Controller {
    private $parent_type;

    public function __construct($type) {
        parent::__construct($type);
    }
}
var_dump((new TemplateController('page'))->type());

// get_object_vars and foreach see what the scope may access
class Iri {
    protected $ihost = 'example.com';
    private $port = 80;
    public $scheme = 'http';

    public function __get($name) {
        $props = get_object_vars($this);
        return array_key_exists($name, $props) ? $this->$name : null;
    }

    public function keys() {
        $keys = [];
        foreach ($this as $key => $value) {
            $keys[] = $key;
        }
        return $keys;
    }
}
$iri = new Iri;
var_dump($iri->ihost, get_object_vars($iri), $iri->keys());
foreach ($iri as $key => $value) {
    echo "$key\n";
}
var_dump(array_map('get_object_vars', [$iri]));
