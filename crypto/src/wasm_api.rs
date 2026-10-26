//! wasm-bindgen bindings (browser). Thin wrapper around [`Client`].
//!
//! Byte arrays cross the boundary as `Uint8Array` (copied). An error becomes a JS `Error` whose
//! message is exactly an [`ErrorCode`] name and nothing else. A panic is a wasm trap here (no
//! unwinding on wasm32): it surfaces in JS as `WebAssembly.RuntimeError`, which the Kotlin side
//! treats as "this module instance is dead" (ADR-0007).

use wasm_bindgen::prelude::*;

use crate::auth::AuthPurpose;
use crate::client::Client;
use crate::error::{ErrorCode, PanicGuard, catch, install_silent_panic_hook, module_call};
use crate::group::{EncryptedMessage, IncomingMessage, LeafInfo, PreparedCommit, WelcomeInfo};
use crate::identity::{CreatedKeyPackage, PublicIdentity};
use crate::list::{decode_byte_lists, decode_u32_list};
use crate::snapshot::take_key;

fn js_err(code: ErrorCode) -> JsError {
    JsError::new(&code.to_string())
}

/// See [`PublicIdentity`]. Getters copy bytes the caller already received (no MLS state).
#[wasm_bindgen(js_name = PublicIdentity)]
pub struct WasmPublicIdentity(PublicIdentity);

#[wasm_bindgen(js_class = PublicIdentity)]
impl WasmPublicIdentity {
    #[wasm_bindgen(getter)]
    pub fn username(&self) -> String {
        self.0.username.clone()
    }

    #[wasm_bindgen(getter, js_name = signaturePublicKey)]
    pub fn signature_public_key(&self) -> Vec<u8> {
        self.0.signature_public_key.clone()
    }
}

/// See [`CreatedKeyPackage`]. Getters copy bytes the caller already received (no MLS state).
#[wasm_bindgen(js_name = CreatedKeyPackage)]
pub struct WasmCreatedKeyPackage(CreatedKeyPackage);

#[wasm_bindgen(js_class = CreatedKeyPackage)]
impl WasmCreatedKeyPackage {
    #[wasm_bindgen(getter, js_name = keyPackage)]
    pub fn key_package(&self) -> Vec<u8> {
        self.0.key_package.clone()
    }

    #[wasm_bindgen(getter, js_name = keyPackageRef)]
    pub fn key_package_ref(&self) -> Vec<u8> {
        self.0.key_package_ref.clone()
    }

    /// Unix seconds, as a JS number (exact below 2^53).
    #[wasm_bindgen(getter, js_name = notAfter)]
    pub fn not_after(&self) -> f64 {
        self.0.not_after as f64
    }
}

/// See [`LeafInfo`]. Getters copy values the caller already received.
#[wasm_bindgen(js_name = LeafInfo)]
pub struct WasmLeafInfo(LeafInfo);

#[wasm_bindgen(js_class = LeafInfo)]
impl WasmLeafInfo {
    #[wasm_bindgen(getter)]
    pub fn username(&self) -> String {
        self.0.username.clone()
    }

    #[wasm_bindgen(getter, js_name = signatureKey)]
    pub fn signature_key(&self) -> Vec<u8> {
        self.0.signature_key.clone()
    }

    #[wasm_bindgen(getter, js_name = leafIndex)]
    pub fn leaf_index(&self) -> u32 {
        self.0.leaf_index
    }
}

fn leaves(items: &[LeafInfo]) -> Vec<WasmLeafInfo> {
    items.iter().cloned().map(WasmLeafInfo).collect()
}

/// See [`PreparedCommit`]. `welcome` is empty when the commit adds nobody.
#[wasm_bindgen(js_name = PreparedCommit)]
pub struct WasmPreparedCommit(PreparedCommit);

#[wasm_bindgen(js_class = PreparedCommit)]
impl WasmPreparedCommit {
    #[wasm_bindgen(getter)]
    pub fn commit(&self) -> Vec<u8> {
        self.0.commit.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn welcome(&self) -> Vec<u8> {
        self.0.welcome.clone().unwrap_or_default()
    }

    /// Epoch numbers stay far below 2^53.
    #[wasm_bindgen(getter)]
    pub fn epoch(&self) -> f64 {
        self.0.epoch as f64
    }

    #[wasm_bindgen(getter)]
    pub fn added(&self) -> Vec<WasmLeafInfo> {
        leaves(&self.0.added)
    }

    #[wasm_bindgen(getter)]
    pub fn removed(&self) -> Vec<WasmLeafInfo> {
        leaves(&self.0.removed)
    }
}

/// See [`EncryptedMessage`].
#[wasm_bindgen(js_name = EncryptedMessage)]
pub struct WasmEncryptedMessage(EncryptedMessage);

#[wasm_bindgen(js_class = EncryptedMessage)]
impl WasmEncryptedMessage {
    #[wasm_bindgen(getter)]
    pub fn message(&self) -> Vec<u8> {
        self.0.message.clone()
    }

    #[wasm_bindgen(getter, js_name = messageRef)]
    pub fn message_ref(&self) -> Vec<u8> {
        self.0.message_ref.clone()
    }
}

/// See [`IncomingMessage`].
#[wasm_bindgen(js_name = IncomingMessage)]
pub struct WasmIncomingMessage(IncomingMessage);

#[wasm_bindgen(js_class = IncomingMessage)]
impl WasmIncomingMessage {
    #[wasm_bindgen(getter, js_name = isCommit)]
    pub fn is_commit(&self) -> bool {
        self.0.is_commit
    }

    #[wasm_bindgen(getter)]
    pub fn token(&self) -> u32 {
        self.0.token
    }

    #[wasm_bindgen(getter)]
    pub fn plaintext(&self) -> Vec<u8> {
        self.0.plaintext.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn sender(&self) -> WasmLeafInfo {
        WasmLeafInfo(self.0.sender.clone())
    }

    #[wasm_bindgen(getter, js_name = messageRef)]
    pub fn message_ref(&self) -> Vec<u8> {
        self.0.message_ref.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn epoch(&self) -> f64 {
        self.0.epoch as f64
    }

    #[wasm_bindgen(getter)]
    pub fn added(&self) -> Vec<WasmLeafInfo> {
        leaves(&self.0.added)
    }

    #[wasm_bindgen(getter)]
    pub fn removed(&self) -> Vec<WasmLeafInfo> {
        leaves(&self.0.removed)
    }

    #[wasm_bindgen(getter, js_name = selfRemoved)]
    pub fn self_removed(&self) -> bool {
        self.0.self_removed
    }
}

/// See [`WelcomeInfo`].
#[wasm_bindgen(js_name = WelcomeInfo)]
pub struct WasmWelcomeInfo(WelcomeInfo);

#[wasm_bindgen(js_class = WelcomeInfo)]
impl WasmWelcomeInfo {
    #[wasm_bindgen(getter)]
    pub fn token(&self) -> u32 {
        self.0.token
    }

    #[wasm_bindgen(getter, js_name = groupId)]
    pub fn group_id(&self) -> Vec<u8> {
        self.0.group_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn sender(&self) -> WasmLeafInfo {
        WasmLeafInfo(self.0.sender.clone())
    }

    #[wasm_bindgen(getter)]
    pub fn members(&self) -> Vec<WasmLeafInfo> {
        leaves(&self.0.members)
    }
}

/// One MLS client (one browser profile). See [`Client`].
#[wasm_bindgen]
pub struct NativeMlsClient {
    guard: PanicGuard,
    client: Client,
}

impl NativeMlsClient {
    fn wrap(client: Client) -> Self {
        Self {
            guard: PanicGuard::default(),
            client,
        }
    }
}

#[wasm_bindgen]
impl NativeMlsClient {
    /// Creates a new identity for `username`. `snapshot_key` (32 bytes) encrypts every state
    /// export; it is copied and its copy in wasm memory is wiped (best effort, ADR-0007).
    #[wasm_bindgen(constructor)]
    pub fn new(username: String, snapshot_key: Vec<u8>) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::new(username, take_key(snapshot_key)?)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    /// Restores a client from [`NativeMlsClient::export_state`] output and the same key.
    pub fn restore(state: &[u8], snapshot_key: Vec<u8>) -> Result<NativeMlsClient, JsError> {
        install_silent_panic_hook();
        module_call(|| catch(|| Client::restore(state, take_key(snapshot_key)?)))
            .map(Self::wrap)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = publicIdentity)]
    pub fn public_identity(&self) -> Result<WasmPublicIdentity, JsError> {
        self.guard
            .run(|| Ok(self.client.public_identity()))
            .map(WasmPublicIdentity)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = createKeyPackages)]
    pub fn create_key_packages(&self, count: u32) -> Result<Vec<WasmCreatedKeyPackage>, JsError> {
        self.guard
            .run(|| self.client.create_key_packages(count))
            .map(|kps| kps.into_iter().map(WasmCreatedKeyPackage).collect())
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = pruneExpiredKeyPackages)]
    pub fn prune_expired_key_packages(&self) -> Result<u32, JsError> {
        self.guard
            .run(|| self.client.prune_expired_key_packages())
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = inspectKeyPackage)]
    pub fn inspect_key_package(
        &self,
        key_package: &[u8],
        expected_username: &str,
    ) -> Result<WasmPublicIdentity, JsError> {
        self.guard
            .run(|| {
                self.client
                    .inspect_key_package(key_package, expected_username)
            })
            .map(WasmPublicIdentity)
            .map_err(js_err)
    }

    /// The fingerprint of a 32-byte signature public key: 12 indices into the app's emoji table (ADR-0014).
    pub fn fingerprint(&self, signature_key: &[u8]) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.fingerprint(signature_key))
            .map_err(js_err)
    }

    /// The whole client state, encrypted with the snapshot key.
    #[wasm_bindgen(js_name = exportState)]
    pub fn export_state(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.export_state())
            .map_err(js_err)
    }

    /// The whole client state under a **new** key; the key switches only if the export succeeded (ADR-0011).
    #[wasm_bindgen(js_name = rekeyAndExport)]
    pub fn rekey_and_export(&self, new_key: Vec<u8>) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.rekey_and_export(take_key(new_key)?))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = exportsUnderKey)]
    pub fn exports_under_key(&self) -> Result<f64, JsError> {
        self.guard
            .run(|| self.client.exports_under_key())
            .map(|n| n as f64)
            .map_err(js_err)
    }

    /// The sync engine's opaque state, exported inside the next snapshot (at most 256 KiB).
    #[wasm_bindgen(js_name = setAppState)]
    pub fn set_app_state(&self, state: Vec<u8>) -> Result<(), JsError> {
        self.guard
            .run(|| self.client.set_app_state(state))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = appState)]
    pub fn app_state(&self) -> Result<Vec<u8>, JsError> {
        self.guard.run(|| self.client.app_state()).map_err(js_err)
    }

    // Group lifecycle (ADR-0016). Lists cross as one `Uint8Array` (see `crate::list`).

    #[wasm_bindgen(js_name = createGroup)]
    pub fn create_group(&self) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.create_group())
            .map_err(js_err)
    }

    /// `key_packages`: each KeyPackage as `u32-BE length ‖ bytes`, one after the other.
    #[wasm_bindgen(js_name = prepareAdd)]
    pub fn prepare_add(
        &self,
        group_id: &[u8],
        key_packages: &[u8],
    ) -> Result<WasmPreparedCommit, JsError> {
        self.guard
            .run(|| {
                self.client
                    .prepare_add(group_id, &decode_byte_lists(key_packages)?)
            })
            .map(WasmPreparedCommit)
            .map_err(js_err)
    }

    /// `leaf_indexes`: each index as `u32-BE`, one after the other.
    #[wasm_bindgen(js_name = prepareRemove)]
    pub fn prepare_remove(
        &self,
        group_id: &[u8],
        leaf_indexes: &[u8],
    ) -> Result<WasmPreparedCommit, JsError> {
        self.guard
            .run(|| {
                self.client
                    .prepare_remove(group_id, &decode_u32_list(leaf_indexes)?)
            })
            .map(WasmPreparedCommit)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = prepareUpdate)]
    pub fn prepare_update(&self, group_id: &[u8]) -> Result<WasmPreparedCommit, JsError> {
        self.guard
            .run(|| self.client.prepare_update(group_id))
            .map(WasmPreparedCommit)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = mergePending)]
    pub fn merge_pending(&self, group_id: &[u8]) -> Result<f64, JsError> {
        self.guard
            .run(|| self.client.merge_pending(group_id))
            .map(|epoch| epoch as f64)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = clearPending)]
    pub fn clear_pending(&self, group_id: &[u8]) -> Result<(), JsError> {
        self.guard
            .run(|| self.client.clear_pending(group_id))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = hasPendingCommit)]
    pub fn has_pending_commit(&self, group_id: &[u8]) -> Result<bool, JsError> {
        self.guard
            .run(|| self.client.has_pending_commit(group_id))
            .map_err(js_err)
    }

    pub fn encrypt(
        &self,
        group_id: &[u8],
        plaintext: &[u8],
    ) -> Result<WasmEncryptedMessage, JsError> {
        self.guard
            .run(|| self.client.encrypt(group_id, plaintext))
            .map(WasmEncryptedMessage)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = stageIncoming)]
    pub fn stage_incoming(
        &self,
        group_id: &[u8],
        message: &[u8],
    ) -> Result<WasmIncomingMessage, JsError> {
        self.guard
            .run(|| self.client.stage_incoming(group_id, message))
            .map(|incoming| WasmIncomingMessage(incoming.into()))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = mergeStaged)]
    pub fn merge_staged(&self, token: u32) -> Result<f64, JsError> {
        self.guard
            .run(|| self.client.merge_staged(token))
            .map(|epoch| epoch as f64)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = discardStaged)]
    pub fn discard_staged(&self, token: u32) -> Result<(), JsError> {
        self.guard
            .run(|| self.client.discard_staged(token))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = inspectWelcome)]
    pub fn inspect_welcome(
        &self,
        welcome: &[u8],
        expected_group_id: &[u8],
    ) -> Result<WasmWelcomeInfo, JsError> {
        self.guard
            .run(|| self.client.inspect_welcome(welcome, expected_group_id))
            .map(WasmWelcomeInfo)
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = joinWelcome)]
    pub fn join_welcome(&self, token: u32) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| self.client.join_welcome(token))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = discardWelcome)]
    pub fn discard_welcome(&self, token: u32) -> Result<(), JsError> {
        self.guard
            .run(|| self.client.discard_welcome(token))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = forgetGroup)]
    pub fn forget_group(&self, group_id: &[u8]) -> Result<(), JsError> {
        self.guard
            .run(|| self.client.forget_group(group_id))
            .map_err(js_err)
    }

    pub fn members(&self, group_id: &[u8]) -> Result<Vec<WasmLeafInfo>, JsError> {
        self.guard
            .run(|| self.client.members(group_id))
            .map(|items| leaves(&items))
            .map_err(js_err)
    }

    #[wasm_bindgen(js_name = memberCount)]
    pub fn member_count(&self, group_id: &[u8]) -> Result<u32, JsError> {
        self.guard
            .run(|| self.client.member_count(group_id))
            .map_err(js_err)
    }

    pub fn epoch(&self, group_id: &[u8]) -> Result<f64, JsError> {
        self.guard
            .run(|| self.client.epoch(group_id))
            .map(|epoch| epoch as f64)
            .map_err(js_err)
    }

    /// Signs the server's login or registration challenge (ADR-0009). `purpose` is exactly `"register"` or
    /// `"login"`; anything else is `InvalidInput`. No FFI function signs arbitrary bytes.
    #[wasm_bindgen(js_name = signAuth)]
    pub fn sign_auth(
        &self,
        purpose: &str,
        challenge: &[u8],
        origin: &str,
        subject: &str,
    ) -> Result<Vec<u8>, JsError> {
        self.guard
            .run(|| {
                let purpose = AuthPurpose::from_name(purpose)?;
                self.client.sign_auth(purpose, challenge, origin, subject)
            })
            .map_err(js_err)
    }
}
