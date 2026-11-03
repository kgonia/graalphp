<?php
// Call sites whose receivers are of many classes that inherit one method, as WooCommerce's getters call
// `$this->get_prop()` on every product and order class: a site calls per method found (MethodLookupNode,
// MethodDispatchNode), so it calls the one method directly and inlines it. Keyed on the receiver's class, a site
// had four entries, then went megamorphic with an indirect call. The budgets: about twice the IR the functions
// compile to now (2026-11-02: total 581-626 with price and getProp inlined at the last tier, Data::price
// 162-193; keyed on the class, total 689-824 with no call inlined).
// IR budget: total 1200
// IR budget: Data::price 400
abstract class Data {
    protected $data = ['price' => 1];
    public function __construct($p) { $this->data['price'] = $p; }
    protected function getProp($name) { return $this->data[$name]; }
    public function price() { return $this->getProp('price'); }
}
class Simple extends Data {}
class Variable extends Data {}
class Variation extends Variable {}
class Grouped extends Data {}
class External extends Data {}
class Order extends Data {}
function total(array $items) {
    $sum = 0;
    foreach ($items as $item) {
        $sum += $item->price();
    }
    return $sum;
}
$items = [];
foreach (['Simple', 'Variable', 'Variation', 'Grouped', 'External', 'Order'] as $i => $class) {
    $items[] = new $class($i);
}
$sum = 0;
for ($i = 0; $i < 3000; $i++) {
    $sum += total($items);
}
echo $sum, "\n";
