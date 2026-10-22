package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;

/** The value types Truffle DSL specializations dispatch on; an int widens to float where a float is expected. */
@TypeSystem({long.class, double.class, boolean.class, TruffleString.class, PhpArray.class, PhpObject.class,
        PhpClosure.class, PhpNull.class})
public abstract class PhpTypes {
    @ImplicitCast
    public static double castDouble(long value) {
        return value;
    }
}
