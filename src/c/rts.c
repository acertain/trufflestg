#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// TODO
extern int64_t base_GHCziTopHandler_runIO_closure[] = {};

// TODO: figure out how to deal with foreign exports
// look at Module.foreignStubs
extern int64_t base_ControlziConcurrent_zdfstableZZC0ZZCbaseZZCControlzziConcurrentZZCforkOSzzuentry_closure[] = {};

extern bool keepCAFs = false;

extern void* stable_ptr_table = NULL;
extern unsigned int n_capabilities = 4;
extern int64_t RtsFlags[1048576] = {0}; // is actually a struct, allocate 8MB to be safe

void unblockUserSignals(void) {}
void blockUserSignals(void) {}

// used for profiling, so we don't need it i think
void startTimer(void) {}
void stopTimer(void) {}


// TODO
void debugBelch() {}
void errorBelch() {}
void _assertFail() {}
void rts_mkStablePtr() {}
void rts_unlock() {}
void rts_lock() {}
void rts_checkSchedStatus() {}
void rts_mkInt32() {}
void rts_getInt32() {}
void rts_evalIO() {}
void rts_apply() {}
void foreignExportStablePtr() {}
void getProcessElapsedTime() {}

void __dummy_hfehfewhoihjofijwoiefhua() {}
