<?php
// A large method in the style of WordPress's WP_Query::get_posts (about 1,700 lines, 9,000 AST nodes): it
// writes and reads its query variables through a reference to a property, tests them with isset(), empty() and
// ??, builds SQL with ., .= and interpolation, and calls implode()/array_map(). Its compiled code per operation
// is what decides whether such a method fits HotSpot's install limit (640 KB), so it has a budget of IR nodes
// (see CompilationTest). Generated; each block repeats one pattern with its own key.
// IR budget: Query::sql 24000
final class Query {
    public array $vars = [];
    public array $flags = [];
    public string $table = 'wp_posts';
    public function __construct(array $vars) { $this->vars = $vars; }
    public function sql(): string {
        $q = &$this->vars;
        $where = '';
        $join = '';
        $parts = [];
        if (!empty($q['k0'])) {
            $where .= " AND {$this->table}.k0 = '" . $q['k0'] . "'";
        }
        $q['k1'] ??= 'd1';
        if (isset($q['k1']) && '' !== $q['k1']) {
            $parts[] = "k1={$q['k1']}";
        }
        $this->flags['k2'] = !empty($q['k2']);
        if ($this->flags['k2']) {
            $join .= ' JOIN t2 ON t2.id = ' . $this->table . '.id';
        }
        if (!empty($q['k3']) && is_array($q['k3'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k3'])) . ')';
        } elseif (isset($q['k3']) && is_numeric($q['k3'])) {
            $q['k3'] = (int) $q['k3'];
            $where .= ' AND n3 = ' . $q['k3'];
        }
        $value = $q['k4'] ?? '';
        if ('' !== $value) {
            $q['k4'] = strtolower($value);
            $where .= " OR k4 LIKE '%{$q['k4']}%'";
        }
        if (!empty($q['k5'])) {
            $where .= " AND {$this->table}.k5 = '" . $q['k5'] . "'";
        }
        $q['k6'] ??= 'd6';
        if (isset($q['k6']) && '' !== $q['k6']) {
            $parts[] = "k6={$q['k6']}";
        }
        $this->flags['k7'] = !empty($q['k7']);
        if ($this->flags['k7']) {
            $join .= ' JOIN t7 ON t7.id = ' . $this->table . '.id';
        }
        if (!empty($q['k8']) && is_array($q['k8'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k8'])) . ')';
        } elseif (isset($q['k8']) && is_numeric($q['k8'])) {
            $q['k8'] = (int) $q['k8'];
            $where .= ' AND n8 = ' . $q['k8'];
        }
        $value = $q['k9'] ?? '';
        if ('' !== $value) {
            $q['k9'] = strtolower($value);
            $where .= " OR k9 LIKE '%{$q['k9']}%'";
        }
        if (!empty($q['k10'])) {
            $where .= " AND {$this->table}.k10 = '" . $q['k10'] . "'";
        }
        $q['k11'] ??= 'd11';
        if (isset($q['k11']) && '' !== $q['k11']) {
            $parts[] = "k11={$q['k11']}";
        }
        $this->flags['k12'] = !empty($q['k12']);
        if ($this->flags['k12']) {
            $join .= ' JOIN t12 ON t12.id = ' . $this->table . '.id';
        }
        if (!empty($q['k13']) && is_array($q['k13'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k13'])) . ')';
        } elseif (isset($q['k13']) && is_numeric($q['k13'])) {
            $q['k13'] = (int) $q['k13'];
            $where .= ' AND n13 = ' . $q['k13'];
        }
        $value = $q['k14'] ?? '';
        if ('' !== $value) {
            $q['k14'] = strtolower($value);
            $where .= " OR k14 LIKE '%{$q['k14']}%'";
        }
        if (!empty($q['k15'])) {
            $where .= " AND {$this->table}.k15 = '" . $q['k15'] . "'";
        }
        $q['k16'] ??= 'd16';
        if (isset($q['k16']) && '' !== $q['k16']) {
            $parts[] = "k16={$q['k16']}";
        }
        $this->flags['k17'] = !empty($q['k17']);
        if ($this->flags['k17']) {
            $join .= ' JOIN t17 ON t17.id = ' . $this->table . '.id';
        }
        if (!empty($q['k18']) && is_array($q['k18'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k18'])) . ')';
        } elseif (isset($q['k18']) && is_numeric($q['k18'])) {
            $q['k18'] = (int) $q['k18'];
            $where .= ' AND n18 = ' . $q['k18'];
        }
        $value = $q['k19'] ?? '';
        if ('' !== $value) {
            $q['k19'] = strtolower($value);
            $where .= " OR k19 LIKE '%{$q['k19']}%'";
        }
        if (!empty($q['k20'])) {
            $where .= " AND {$this->table}.k20 = '" . $q['k20'] . "'";
        }
        $q['k21'] ??= 'd21';
        if (isset($q['k21']) && '' !== $q['k21']) {
            $parts[] = "k21={$q['k21']}";
        }
        $this->flags['k22'] = !empty($q['k22']);
        if ($this->flags['k22']) {
            $join .= ' JOIN t22 ON t22.id = ' . $this->table . '.id';
        }
        if (!empty($q['k23']) && is_array($q['k23'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k23'])) . ')';
        } elseif (isset($q['k23']) && is_numeric($q['k23'])) {
            $q['k23'] = (int) $q['k23'];
            $where .= ' AND n23 = ' . $q['k23'];
        }
        $value = $q['k24'] ?? '';
        if ('' !== $value) {
            $q['k24'] = strtolower($value);
            $where .= " OR k24 LIKE '%{$q['k24']}%'";
        }
        if (!empty($q['k25'])) {
            $where .= " AND {$this->table}.k25 = '" . $q['k25'] . "'";
        }
        $q['k26'] ??= 'd26';
        if (isset($q['k26']) && '' !== $q['k26']) {
            $parts[] = "k26={$q['k26']}";
        }
        $this->flags['k27'] = !empty($q['k27']);
        if ($this->flags['k27']) {
            $join .= ' JOIN t27 ON t27.id = ' . $this->table . '.id';
        }
        if (!empty($q['k28']) && is_array($q['k28'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k28'])) . ')';
        } elseif (isset($q['k28']) && is_numeric($q['k28'])) {
            $q['k28'] = (int) $q['k28'];
            $where .= ' AND n28 = ' . $q['k28'];
        }
        $value = $q['k29'] ?? '';
        if ('' !== $value) {
            $q['k29'] = strtolower($value);
            $where .= " OR k29 LIKE '%{$q['k29']}%'";
        }
        if (!empty($q['k30'])) {
            $where .= " AND {$this->table}.k30 = '" . $q['k30'] . "'";
        }
        $q['k31'] ??= 'd31';
        if (isset($q['k31']) && '' !== $q['k31']) {
            $parts[] = "k31={$q['k31']}";
        }
        $this->flags['k32'] = !empty($q['k32']);
        if ($this->flags['k32']) {
            $join .= ' JOIN t32 ON t32.id = ' . $this->table . '.id';
        }
        if (!empty($q['k33']) && is_array($q['k33'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k33'])) . ')';
        } elseif (isset($q['k33']) && is_numeric($q['k33'])) {
            $q['k33'] = (int) $q['k33'];
            $where .= ' AND n33 = ' . $q['k33'];
        }
        $value = $q['k34'] ?? '';
        if ('' !== $value) {
            $q['k34'] = strtolower($value);
            $where .= " OR k34 LIKE '%{$q['k34']}%'";
        }
        if (!empty($q['k35'])) {
            $where .= " AND {$this->table}.k35 = '" . $q['k35'] . "'";
        }
        $q['k36'] ??= 'd36';
        if (isset($q['k36']) && '' !== $q['k36']) {
            $parts[] = "k36={$q['k36']}";
        }
        $this->flags['k37'] = !empty($q['k37']);
        if ($this->flags['k37']) {
            $join .= ' JOIN t37 ON t37.id = ' . $this->table . '.id';
        }
        if (!empty($q['k38']) && is_array($q['k38'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k38'])) . ')';
        } elseif (isset($q['k38']) && is_numeric($q['k38'])) {
            $q['k38'] = (int) $q['k38'];
            $where .= ' AND n38 = ' . $q['k38'];
        }
        $value = $q['k39'] ?? '';
        if ('' !== $value) {
            $q['k39'] = strtolower($value);
            $where .= " OR k39 LIKE '%{$q['k39']}%'";
        }
        return 'SELECT * FROM ' . $this->table . $join . ' WHERE 1=1' . $where . ' /* ' . implode('&', $parts) . ' */';
    }
}
$total = 0;
for ($n = 0; $n < 600; $n++) {
    $vars = [];
    for ($i = 0; $i < 40; $i++) {
        $key = 'k' . $i;
        switch (($n + $i) % 3) {
            case 0: $vars[$key] = $i % 5 === 3 ? [$n, $i, '3'] : 'v' . $n; break;
            case 1: $vars[$key] = (string) ($n * $i); break;
            default: break;
        }
    }
    $total += strlen((new Query($vars))->sql());
}
echo $total, "
";
