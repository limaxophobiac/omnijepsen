use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{get, put},
    Router,
};
use serde::Serialize;
use std::env;
use std::{collections::HashMap, net::SocketAddr};
use tokio::sync::{mpsc, oneshot};
use omnipaxos_kv::common::{
    kv::{CommandId, KVCommand},
    messages::{ClientMessage, ServerMessage},
};

mod configs;
mod network;

#[derive(Clone)]
struct AppState {
    cmd_tx: mpsc::Sender<ApiCommand>,
}

enum ApiCommand {
    Put {
        key: String,
        value: String,
        reply_to: oneshot::Sender<Result<(), StatusCode>>,
    },
    Get {
        key: String,
        reply_to: oneshot::Sender<Result<Option<String>, StatusCode>>,
    },
}

#[derive(Serialize)]
struct ApiResponse {
    ok: bool,
    value: Option<String>,
    error: Option<String>,
}

impl ApiResponse {
    fn ok(value: Option<String>) -> Self {
        Self {
            ok: true,
            value,
            error: None,
        }
    }

    fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            value: None,
            error: Some(msg.into()),
        }
    }
}

#[tokio::main]
async fn main() {
    env_logger::init();
    let client_config = configs::ClientConfig::new().expect("Failed to parse CONFIG_FILE");
    let active_server = client_config.server_id;
    let server_address = client_config.server_address.clone();
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<ApiCommand>(128);
    tokio::spawn(async move {
        let mut network = network::Network::new(vec![(active_server, server_address)], 100).await;

        loop {
            match network.server_messages.recv().await {
                Some(ServerMessage::StartSignal(_)) => break,
                Some(_) => {}
                None => return,
            }
        }

        let mut next_request_id: CommandId = 0;
        let mut pending_put: HashMap<CommandId, oneshot::Sender<Result<(), StatusCode>>> = HashMap::new();
        let mut pending_get: HashMap<CommandId,oneshot::Sender<Result<Option<String>, StatusCode>>,> = HashMap::new();

        loop {
            tokio::select! {
                Some(cmd) = cmd_rx.recv() => {
                    match cmd {
                        ApiCommand::Put {
                            key,
                            value,
                            reply_to,
                        } => {
                            let request_id = next_request_id;
                            next_request_id += 1;

                            pending_put.insert(request_id, reply_to);
                            let msg = ClientMessage::Append(request_id, KVCommand::Put(key.clone(), value.clone()));
                            network.send(active_server, msg).await;
                        }
                        ApiCommand::Get { 
                            key, 
                            reply_to 
                        } => {
                            let request_id = next_request_id;
                            next_request_id += 1;
                            pending_get.insert(request_id, reply_to);
                            let msg = ClientMessage::Append(request_id, KVCommand::Get(key));
                            network.send(active_server, msg).await;
                        }
                    }
                }

                Some(server_msg) = network.server_messages.recv() => {
                    match server_msg {
                        ServerMessage::Write(id) => {
                            if let Some(tx) = pending_put.remove(&id) {
                                let _ = tx.send(Ok(()));
                            }
                        }
                        ServerMessage::Read(id, value) => {
                            if let Some(tx) = pending_get.remove(&id) {
                                let _ = tx.send(Ok(value));
                            }
                        }
                        ServerMessage::StartSignal(_) => {}
                    }
                }

                else => break,
            }
        }



        network.shutdown();
    });

    let state = AppState {
        cmd_tx,
    };

    let app = Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/kv/:key", put(put_key).get(get_key))
        .with_state(state);

    let addr: SocketAddr = env::var("API_LISTEN_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:7000".to_string())
        .parse()
        .unwrap();
    println!("api-shim listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn put_key(
    State(state): State<AppState>,
    Path(key): Path<String>,
    body: String,
) -> StatusCode {
    let (reply_to, reply_rx) = oneshot::channel();
    let cmd = ApiCommand::Put {
        key,
        value: body,
        reply_to,
    };

    if state.cmd_tx.send(cmd).await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR;
    }

    match reply_rx.await {
        Ok(Ok(())) => StatusCode::NO_CONTENT,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

async fn get_key(
    State(state): State<AppState>,
    Path(key): Path<String>,
) -> Result<String, StatusCode> {
    let (reply_to, reply_rx) = oneshot::channel();
    let cmd = ApiCommand::Get { key, reply_to };

    if state.cmd_tx.send(cmd).await.is_err() {
        return Err(StatusCode::INTERNAL_SERVER_ERROR);
    }

    match reply_rx.await {
        Ok(Ok(Some(value))) => Ok(value),
        Ok(Ok(None)) => Err(StatusCode::NOT_FOUND),
        _ => Err(StatusCode::INTERNAL_SERVER_ERROR),
    }
}