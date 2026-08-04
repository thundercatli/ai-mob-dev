#ifndef AIDEVMOB_FRPC_CORE_H
#define AIDEVMOB_FRPC_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum aidevmob_frpc_state {
    AIDEVMOB_FRPC_STATE_STOPPED = 0,
    AIDEVMOB_FRPC_STATE_STARTING = 1,
    AIDEVMOB_FRPC_STATE_RUNNING = 2,
    AIDEVMOB_FRPC_STATE_ERROR = 3
} aidevmob_frpc_state;

typedef struct aidevmob_frpc_stcp_config {
    const char* server_host;
    uint16_t server_port;
    const char* server_name;
    const char* secret_key;
    const char* bind_host;
    uint16_t bind_port;
    int connect_timeout_ms;
    const char* auth_token;
    const char* user;
    const char* server_user;
    int use_tls;
    int tcp_mux;
    int use_encryption;
    int use_compression;
} aidevmob_frpc_stcp_config;

typedef void (*aidevmob_frpc_state_callback)(
    void* context,
    aidevmob_frpc_state state,
    const char* detail
);

typedef void (*aidevmob_frpc_log_callback)(void* context, const char* line);
typedef int (*aidevmob_frpc_open_transport_callback)(
    void* context,
    const char* host,
    uint16_t port,
    int use_tls,
    int timeout_ms
);

typedef struct aidevmob_frpc_callbacks {
    void* context;
    aidevmob_frpc_state_callback on_state;
    aidevmob_frpc_log_callback on_log;
    aidevmob_frpc_open_transport_callback open_transport;
} aidevmob_frpc_callbacks;

typedef struct aidevmob_frpc_core aidevmob_frpc_core;

aidevmob_frpc_core* aidevmob_frpc_core_create(
    const aidevmob_frpc_stcp_config* config,
    const aidevmob_frpc_callbacks* callbacks
);

int aidevmob_frpc_core_start(aidevmob_frpc_core* core);

void aidevmob_frpc_core_stop(aidevmob_frpc_core* core);

void aidevmob_frpc_core_destroy(aidevmob_frpc_core* core);

#ifdef __cplusplus
}
#endif

#endif
