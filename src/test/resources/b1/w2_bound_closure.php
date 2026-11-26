<?php
declare(strict_types=1);
class CallbackOwner {
    public static int $count = 0;
    public array $data = [];
    public function callback(): callable {
        return function (string $key): void {
            $q = &$this->data;
            $q[$key] ??= 'value';
            self::$count++;
            if (!empty($q[$key])) echo "{$q[$key]}:", self::$count, "\n";
        };
    }
    public function __destruct() {
        global $saved;
        $saved = $this;
        echo "released\n";
    }
}
$obj = new CallbackOwner();
$fn = $obj->callback();
unset($obj);
$fn('key');
echo "before unset\n";
unset($fn);
echo CallbackOwner::$count, "\n";
$saved = null;
