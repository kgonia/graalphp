<?php
// query-builder.php's method three times over (120 query variables, 7,920 AST nodes): a function as large
// as those whose compiled code nears HotSpot's install limit (640 KB), WP_Query::get_posts and wp-settings.php's top
// level. In such a function a string-key read outside any loop is a call, without the inline check of its slot
// (ElementNodes.SlotCacheNode, nodes.LargeCode), so its budget is per read about half of query-builder.php's.
// Generated from query-builder.php by repeating its blocks with the keys renumbered.
// IR budget: Query::sql 52000
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
        if (!empty($q['k40'])) {
            $where .= " AND {$this->table}.k40 = '" . $q['k40'] . "'";
        }
        $q['k41'] ??= 'd41';
        if (isset($q['k41']) && '' !== $q['k41']) {
            $parts[] = "k41={$q['k41']}";
        }
        $this->flags['k42'] = !empty($q['k42']);
        if ($this->flags['k42']) {
            $join .= ' JOIN t42 ON t42.id = ' . $this->table . '.id';
        }
        if (!empty($q['k43']) && is_array($q['k43'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k43'])) . ')';
        } elseif (isset($q['k43']) && is_numeric($q['k43'])) {
            $q['k43'] = (int) $q['k43'];
            $where .= ' AND n43 = ' . $q['k43'];
        }
        $value = $q['k44'] ?? '';
        if ('' !== $value) {
            $q['k44'] = strtolower($value);
            $where .= " OR k44 LIKE '%{$q['k44']}%'";
        }
        if (!empty($q['k45'])) {
            $where .= " AND {$this->table}.k45 = '" . $q['k45'] . "'";
        }
        $q['k46'] ??= 'd46';
        if (isset($q['k46']) && '' !== $q['k46']) {
            $parts[] = "k46={$q['k46']}";
        }
        $this->flags['k47'] = !empty($q['k47']);
        if ($this->flags['k47']) {
            $join .= ' JOIN t47 ON t47.id = ' . $this->table . '.id';
        }
        if (!empty($q['k48']) && is_array($q['k48'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k48'])) . ')';
        } elseif (isset($q['k48']) && is_numeric($q['k48'])) {
            $q['k48'] = (int) $q['k48'];
            $where .= ' AND n48 = ' . $q['k48'];
        }
        $value = $q['k49'] ?? '';
        if ('' !== $value) {
            $q['k49'] = strtolower($value);
            $where .= " OR k49 LIKE '%{$q['k49']}%'";
        }
        if (!empty($q['k50'])) {
            $where .= " AND {$this->table}.k50 = '" . $q['k50'] . "'";
        }
        $q['k51'] ??= 'd51';
        if (isset($q['k51']) && '' !== $q['k51']) {
            $parts[] = "k51={$q['k51']}";
        }
        $this->flags['k52'] = !empty($q['k52']);
        if ($this->flags['k52']) {
            $join .= ' JOIN t52 ON t52.id = ' . $this->table . '.id';
        }
        if (!empty($q['k53']) && is_array($q['k53'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k53'])) . ')';
        } elseif (isset($q['k53']) && is_numeric($q['k53'])) {
            $q['k53'] = (int) $q['k53'];
            $where .= ' AND n53 = ' . $q['k53'];
        }
        $value = $q['k54'] ?? '';
        if ('' !== $value) {
            $q['k54'] = strtolower($value);
            $where .= " OR k54 LIKE '%{$q['k54']}%'";
        }
        if (!empty($q['k55'])) {
            $where .= " AND {$this->table}.k55 = '" . $q['k55'] . "'";
        }
        $q['k56'] ??= 'd56';
        if (isset($q['k56']) && '' !== $q['k56']) {
            $parts[] = "k56={$q['k56']}";
        }
        $this->flags['k57'] = !empty($q['k57']);
        if ($this->flags['k57']) {
            $join .= ' JOIN t57 ON t57.id = ' . $this->table . '.id';
        }
        if (!empty($q['k58']) && is_array($q['k58'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k58'])) . ')';
        } elseif (isset($q['k58']) && is_numeric($q['k58'])) {
            $q['k58'] = (int) $q['k58'];
            $where .= ' AND n58 = ' . $q['k58'];
        }
        $value = $q['k59'] ?? '';
        if ('' !== $value) {
            $q['k59'] = strtolower($value);
            $where .= " OR k59 LIKE '%{$q['k59']}%'";
        }
        if (!empty($q['k60'])) {
            $where .= " AND {$this->table}.k60 = '" . $q['k60'] . "'";
        }
        $q['k61'] ??= 'd61';
        if (isset($q['k61']) && '' !== $q['k61']) {
            $parts[] = "k61={$q['k61']}";
        }
        $this->flags['k62'] = !empty($q['k62']);
        if ($this->flags['k62']) {
            $join .= ' JOIN t62 ON t62.id = ' . $this->table . '.id';
        }
        if (!empty($q['k63']) && is_array($q['k63'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k63'])) . ')';
        } elseif (isset($q['k63']) && is_numeric($q['k63'])) {
            $q['k63'] = (int) $q['k63'];
            $where .= ' AND n63 = ' . $q['k63'];
        }
        $value = $q['k64'] ?? '';
        if ('' !== $value) {
            $q['k64'] = strtolower($value);
            $where .= " OR k64 LIKE '%{$q['k64']}%'";
        }
        if (!empty($q['k65'])) {
            $where .= " AND {$this->table}.k65 = '" . $q['k65'] . "'";
        }
        $q['k66'] ??= 'd66';
        if (isset($q['k66']) && '' !== $q['k66']) {
            $parts[] = "k66={$q['k66']}";
        }
        $this->flags['k67'] = !empty($q['k67']);
        if ($this->flags['k67']) {
            $join .= ' JOIN t67 ON t67.id = ' . $this->table . '.id';
        }
        if (!empty($q['k68']) && is_array($q['k68'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k68'])) . ')';
        } elseif (isset($q['k68']) && is_numeric($q['k68'])) {
            $q['k68'] = (int) $q['k68'];
            $where .= ' AND n68 = ' . $q['k68'];
        }
        $value = $q['k69'] ?? '';
        if ('' !== $value) {
            $q['k69'] = strtolower($value);
            $where .= " OR k69 LIKE '%{$q['k69']}%'";
        }
        if (!empty($q['k70'])) {
            $where .= " AND {$this->table}.k70 = '" . $q['k70'] . "'";
        }
        $q['k71'] ??= 'd71';
        if (isset($q['k71']) && '' !== $q['k71']) {
            $parts[] = "k71={$q['k71']}";
        }
        $this->flags['k72'] = !empty($q['k72']);
        if ($this->flags['k72']) {
            $join .= ' JOIN t72 ON t72.id = ' . $this->table . '.id';
        }
        if (!empty($q['k73']) && is_array($q['k73'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k73'])) . ')';
        } elseif (isset($q['k73']) && is_numeric($q['k73'])) {
            $q['k73'] = (int) $q['k73'];
            $where .= ' AND n73 = ' . $q['k73'];
        }
        $value = $q['k74'] ?? '';
        if ('' !== $value) {
            $q['k74'] = strtolower($value);
            $where .= " OR k74 LIKE '%{$q['k74']}%'";
        }
        if (!empty($q['k75'])) {
            $where .= " AND {$this->table}.k75 = '" . $q['k75'] . "'";
        }
        $q['k76'] ??= 'd76';
        if (isset($q['k76']) && '' !== $q['k76']) {
            $parts[] = "k76={$q['k76']}";
        }
        $this->flags['k77'] = !empty($q['k77']);
        if ($this->flags['k77']) {
            $join .= ' JOIN t77 ON t77.id = ' . $this->table . '.id';
        }
        if (!empty($q['k78']) && is_array($q['k78'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k78'])) . ')';
        } elseif (isset($q['k78']) && is_numeric($q['k78'])) {
            $q['k78'] = (int) $q['k78'];
            $where .= ' AND n78 = ' . $q['k78'];
        }
        $value = $q['k79'] ?? '';
        if ('' !== $value) {
            $q['k79'] = strtolower($value);
            $where .= " OR k79 LIKE '%{$q['k79']}%'";
        }
        if (!empty($q['k80'])) {
            $where .= " AND {$this->table}.k80 = '" . $q['k80'] . "'";
        }
        $q['k81'] ??= 'd81';
        if (isset($q['k81']) && '' !== $q['k81']) {
            $parts[] = "k81={$q['k81']}";
        }
        $this->flags['k82'] = !empty($q['k82']);
        if ($this->flags['k82']) {
            $join .= ' JOIN t82 ON t82.id = ' . $this->table . '.id';
        }
        if (!empty($q['k83']) && is_array($q['k83'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k83'])) . ')';
        } elseif (isset($q['k83']) && is_numeric($q['k83'])) {
            $q['k83'] = (int) $q['k83'];
            $where .= ' AND n83 = ' . $q['k83'];
        }
        $value = $q['k84'] ?? '';
        if ('' !== $value) {
            $q['k84'] = strtolower($value);
            $where .= " OR k84 LIKE '%{$q['k84']}%'";
        }
        if (!empty($q['k85'])) {
            $where .= " AND {$this->table}.k85 = '" . $q['k85'] . "'";
        }
        $q['k86'] ??= 'd86';
        if (isset($q['k86']) && '' !== $q['k86']) {
            $parts[] = "k86={$q['k86']}";
        }
        $this->flags['k87'] = !empty($q['k87']);
        if ($this->flags['k87']) {
            $join .= ' JOIN t87 ON t87.id = ' . $this->table . '.id';
        }
        if (!empty($q['k88']) && is_array($q['k88'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k88'])) . ')';
        } elseif (isset($q['k88']) && is_numeric($q['k88'])) {
            $q['k88'] = (int) $q['k88'];
            $where .= ' AND n88 = ' . $q['k88'];
        }
        $value = $q['k89'] ?? '';
        if ('' !== $value) {
            $q['k89'] = strtolower($value);
            $where .= " OR k89 LIKE '%{$q['k89']}%'";
        }
        if (!empty($q['k90'])) {
            $where .= " AND {$this->table}.k90 = '" . $q['k90'] . "'";
        }
        $q['k91'] ??= 'd91';
        if (isset($q['k91']) && '' !== $q['k91']) {
            $parts[] = "k91={$q['k91']}";
        }
        $this->flags['k92'] = !empty($q['k92']);
        if ($this->flags['k92']) {
            $join .= ' JOIN t92 ON t92.id = ' . $this->table . '.id';
        }
        if (!empty($q['k93']) && is_array($q['k93'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k93'])) . ')';
        } elseif (isset($q['k93']) && is_numeric($q['k93'])) {
            $q['k93'] = (int) $q['k93'];
            $where .= ' AND n93 = ' . $q['k93'];
        }
        $value = $q['k94'] ?? '';
        if ('' !== $value) {
            $q['k94'] = strtolower($value);
            $where .= " OR k94 LIKE '%{$q['k94']}%'";
        }
        if (!empty($q['k95'])) {
            $where .= " AND {$this->table}.k95 = '" . $q['k95'] . "'";
        }
        $q['k96'] ??= 'd96';
        if (isset($q['k96']) && '' !== $q['k96']) {
            $parts[] = "k96={$q['k96']}";
        }
        $this->flags['k97'] = !empty($q['k97']);
        if ($this->flags['k97']) {
            $join .= ' JOIN t97 ON t97.id = ' . $this->table . '.id';
        }
        if (!empty($q['k98']) && is_array($q['k98'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k98'])) . ')';
        } elseif (isset($q['k98']) && is_numeric($q['k98'])) {
            $q['k98'] = (int) $q['k98'];
            $where .= ' AND n98 = ' . $q['k98'];
        }
        $value = $q['k99'] ?? '';
        if ('' !== $value) {
            $q['k99'] = strtolower($value);
            $where .= " OR k99 LIKE '%{$q['k99']}%'";
        }
        if (!empty($q['k100'])) {
            $where .= " AND {$this->table}.k100 = '" . $q['k100'] . "'";
        }
        $q['k101'] ??= 'd101';
        if (isset($q['k101']) && '' !== $q['k101']) {
            $parts[] = "k101={$q['k101']}";
        }
        $this->flags['k102'] = !empty($q['k102']);
        if ($this->flags['k102']) {
            $join .= ' JOIN t102 ON t102.id = ' . $this->table . '.id';
        }
        if (!empty($q['k103']) && is_array($q['k103'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k103'])) . ')';
        } elseif (isset($q['k103']) && is_numeric($q['k103'])) {
            $q['k103'] = (int) $q['k103'];
            $where .= ' AND n103 = ' . $q['k103'];
        }
        $value = $q['k104'] ?? '';
        if ('' !== $value) {
            $q['k104'] = strtolower($value);
            $where .= " OR k104 LIKE '%{$q['k104']}%'";
        }
        if (!empty($q['k105'])) {
            $where .= " AND {$this->table}.k105 = '" . $q['k105'] . "'";
        }
        $q['k106'] ??= 'd106';
        if (isset($q['k106']) && '' !== $q['k106']) {
            $parts[] = "k106={$q['k106']}";
        }
        $this->flags['k107'] = !empty($q['k107']);
        if ($this->flags['k107']) {
            $join .= ' JOIN t107 ON t107.id = ' . $this->table . '.id';
        }
        if (!empty($q['k108']) && is_array($q['k108'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k108'])) . ')';
        } elseif (isset($q['k108']) && is_numeric($q['k108'])) {
            $q['k108'] = (int) $q['k108'];
            $where .= ' AND n108 = ' . $q['k108'];
        }
        $value = $q['k109'] ?? '';
        if ('' !== $value) {
            $q['k109'] = strtolower($value);
            $where .= " OR k109 LIKE '%{$q['k109']}%'";
        }
        if (!empty($q['k110'])) {
            $where .= " AND {$this->table}.k110 = '" . $q['k110'] . "'";
        }
        $q['k111'] ??= 'd111';
        if (isset($q['k111']) && '' !== $q['k111']) {
            $parts[] = "k111={$q['k111']}";
        }
        $this->flags['k112'] = !empty($q['k112']);
        if ($this->flags['k112']) {
            $join .= ' JOIN t112 ON t112.id = ' . $this->table . '.id';
        }
        if (!empty($q['k113']) && is_array($q['k113'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k113'])) . ')';
        } elseif (isset($q['k113']) && is_numeric($q['k113'])) {
            $q['k113'] = (int) $q['k113'];
            $where .= ' AND n113 = ' . $q['k113'];
        }
        $value = $q['k114'] ?? '';
        if ('' !== $value) {
            $q['k114'] = strtolower($value);
            $where .= " OR k114 LIKE '%{$q['k114']}%'";
        }
        if (!empty($q['k115'])) {
            $where .= " AND {$this->table}.k115 = '" . $q['k115'] . "'";
        }
        $q['k116'] ??= 'd116';
        if (isset($q['k116']) && '' !== $q['k116']) {
            $parts[] = "k116={$q['k116']}";
        }
        $this->flags['k117'] = !empty($q['k117']);
        if ($this->flags['k117']) {
            $join .= ' JOIN t117 ON t117.id = ' . $this->table . '.id';
        }
        if (!empty($q['k118']) && is_array($q['k118'])) {
            $where .= ' AND id IN (' . implode(',', array_map('intval', $q['k118'])) . ')';
        } elseif (isset($q['k118']) && is_numeric($q['k118'])) {
            $q['k118'] = (int) $q['k118'];
            $where .= ' AND n118 = ' . $q['k118'];
        }
        $value = $q['k119'] ?? '';
        if ('' !== $value) {
            $q['k119'] = strtolower($value);
            $where .= " OR k119 LIKE '%{$q['k119']}%'";
        }
        return 'SELECT * FROM ' . $this->table . $join . ' WHERE 1=1' . $where . ' /* ' . implode('&', $parts) . ' */';
    }
}
$total = 0;
for ($n = 0; $n < 600; $n++) {
    $vars = [];
    for ($i = 0; $i < 120; $i++) {
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
