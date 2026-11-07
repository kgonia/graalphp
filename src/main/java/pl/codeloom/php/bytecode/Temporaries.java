package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/** The hidden locals an operation releases once it used their values (ReleaseAfter): their places in temporaries. */
public final class Temporaries {
    @CompilationFinal(dimensions = 1) final int[] indices;

    public Temporaries(int[] indices) {
        this.indices = indices;
    }
}
