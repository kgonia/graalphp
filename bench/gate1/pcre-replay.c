// Replays the PCRE2 calls that Truffle PHP recorded (-Dphp.pcre2.record, PcreRecorder.java) against PHP's own
// pcre2lib and times them: the regex work of one kernel iteration, the same for both engines. It is Gate 1's native
// time (wiki/gate-1.md): PHP-code speedup = (T_php - T_native) / (T_truffle - T_native).
//
// Matches run as the preg wrappers run them: pcre2_jit_match for a JIT-compiled pattern matched with no option but
// NO_UTF_CHECK, else pcre2_match; one match block per pattern; php-src's limits and 32K..192K JIT stack.
//
// Build (gate1-truffle image; bench/gate1/compare.sh does it):
//   gcc -O2 -I/opt/pcre2-php -o target/pcre-replay bench/gate1/pcre-replay.c -L/opt/pcre2-php -lpcre2-8 -Wl,-rpath,/opt/pcre2-php
// Usage: pcre-replay <record file> [passes]   prints "kernel matches median-ns min-ns" per recorded section
#define PCRE2_CODE_UNIT_WIDTH 8
#include <pcre2.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct { pcre2_code *code; int jit; pcre2_match_data *data; } pattern_t;
typedef struct { uint8_t *bytes; size_t length; } subject_t;
typedef struct { uint32_t pattern, subject; uint64_t start; uint32_t options; } match_t;
typedef struct { char *label; match_t *matches; size_t count, capacity; } section_t;

static uint8_t *input;
static size_t input_length, at;

static uint32_t u32(void) {
    uint32_t v = (uint32_t) input[at] << 24 | (uint32_t) input[at + 1] << 16 | (uint32_t) input[at + 2] << 8
        | input[at + 3];
    at += 4;
    return v;
}

static uint64_t u64(void) {
    uint64_t high = u32();
    return high << 32 | u32();
}

static uint8_t *bytes(size_t *length) {
    *length = u32();
    uint8_t *copy = malloc(*length + 1);
    memcpy(copy, input + at, *length);
    copy[*length] = 0;
    at += *length;
    return copy;
}

static void *grow(void *array, size_t *capacity, size_t needed, size_t item) {
    if (needed <= *capacity) {
        return array;
    }
    while (*capacity < needed) {
        *capacity = *capacity ? *capacity * 2 : 64;
    }
    return realloc(array, *capacity * item);
}

static double now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e9 + t.tv_nsec;
}

static int compare_doubles(const void *a, const void *b) {
    double x = *(const double *) a, y = *(const double *) b;
    return x < y ? -1 : x > y;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: pcre-replay <record file> [passes]\n");
        return 2;
    }
    int passes = argc > 2 ? atoi(argv[2]) : 200;
    FILE *file = fopen(argv[1], "rb");
    if (!file) {
        perror(argv[1]);
        return 1;
    }
    fseek(file, 0, SEEK_END);
    input_length = ftell(file);
    fseek(file, 0, SEEK_SET);
    input = malloc(input_length);
    if (fread(input, 1, input_length, file) != input_length) {
        perror("read");
        return 1;
    }
    fclose(file);

    pcre2_match_context *context = pcre2_match_context_create(NULL);
    pcre2_set_match_limit(context, 1000000);
    pcre2_set_depth_limit(context, 100000);
    pcre2_jit_stack *stack = pcre2_jit_stack_create(32 * 1024, 192 * 1024, NULL);
    pcre2_jit_stack_assign(context, NULL, stack);

    pattern_t *patterns = NULL;
    subject_t *subjects = NULL;
    section_t *sections = NULL;
    size_t pattern_capacity = 0, subject_capacity = 0, section_capacity = 0, section_count = 0;
    section_t *current = NULL;
    while (at < input_length) {
        uint8_t tag = input[at++];
        if (tag == 'K') {
            sections = grow(sections, &section_capacity, section_count + 1, sizeof(section_t));
            current = &sections[section_count++];
            memset(current, 0, sizeof(*current));
            size_t length;
            current->label = (char *) bytes(&length);
        } else if (tag == 'E') {
            current = NULL;
        } else if (tag == 'P') {
            uint32_t id = u32(), options = u32();
            int jit = input[at++];
            size_t length;
            uint8_t *source = bytes(&length);
            patterns = grow(patterns, &pattern_capacity, id + 1, sizeof(pattern_t));
            int error;
            PCRE2_SIZE offset;
            pattern_t *p = &patterns[id];
            p->code = pcre2_compile(source, length, options, &error, &offset, NULL);
            if (!p->code) {
                fprintf(stderr, "pattern %u does not compile\n", id);
                return 1;
            }
            p->jit = jit && pcre2_jit_compile(p->code, PCRE2_JIT_COMPLETE) == 0;
            p->data = pcre2_match_data_create_from_pattern(p->code, NULL);
            free(source);
        } else if (tag == 'S') {
            uint32_t id = u32();
            subjects = grow(subjects, &subject_capacity, id + 1, sizeof(subject_t));
            subjects[id].bytes = bytes(&subjects[id].length);
        } else if (tag == 'M') {
            match_t m;
            m.pattern = u32();
            m.subject = u32();
            m.start = u64();
            m.options = u32();
            current->matches = grow(current->matches, &current->capacity, current->count + 1, sizeof(match_t));
            current->matches[current->count++] = m;
        } else {
            fprintf(stderr, "bad record tag %d at %zu\n", tag, at - 1);
            return 1;
        }
    }

    double *times = malloc(sizeof(double) * passes);
    long sink = 0;
    for (size_t s = 0; s < section_count; s++) {
        section_t *section = &sections[s];
        for (int pass = -5; pass < passes; pass++) {
            double t0 = now_ns();
            for (size_t i = 0; i < section->count; i++) {
                match_t *m = &section->matches[i];
                pattern_t *p = &patterns[m->pattern];
                subject_t *subject = &subjects[m->subject];
                int rc = p->jit && m->options == PCRE2_NO_UTF_CHECK
                    ? pcre2_jit_match(p->code, subject->bytes, subject->length, m->start, m->options, p->data, context)
                    : pcre2_match(p->code, subject->bytes, subject->length, m->start, m->options, p->data, context);
                sink += rc;
            }
            if (pass >= 0) {
                times[pass] = now_ns() - t0;
            }
        }
        qsort(times, passes, sizeof(double), compare_doubles);
        printf("%s %zu %.0f %.0f\n", section->label, section->count, times[passes / 2], times[0]);
    }
    fprintf(stderr, "checksum %ld\n", sink);
    return 0;
}
