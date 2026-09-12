# Shared object storage model

## Scope

Phase 3 moves persistent user artifacts out of application-node storage. The selected
provider is configured by `app.storage.provider`; local development may use `local`, while
the current local integration configuration selects `aliyun-oss`.

Persistent artifacts covered by object storage:

- voice-library samples;
- courseware source PPT/PPTX files;
- generated courseware narration;
- courseware virtual-teacher images;
- generated courseware video;
- voice-note audio and text.

ASR, speaking evaluation, sound-clone and synchronous video-preview uploads remain bounded
temporary work files and are deleted by the request flow. FFmpeg and POI also require local
working copies. These files are not sources of truth: courseware work files can be rebuilt
from the stored object key on another node.

## Metadata and ownership

`stored_object_metadata` records the object key, provider, bucket, content type, byte size,
SHA-256 checksum, owner and creation time. Reads require both object key and owner. Generated
keys include a one-way owner namespace and never use a client-provided filesystem path.

The existing `voice` table continues to hold the same metadata for voice-library records.
Courseware path columns now contain object keys or an object-key prefix, not node-local paths.

## Migration behavior

V7 creates the shared metadata catalog and changes courseware column contracts to object
keys. A SQL migration cannot upload files from an arbitrary old application node. Therefore,
legacy courseware rows with local paths are retained but marked `FAILED` with an explicit
re-upload message. The checked local business database contained zero courseware rows before
this migration was authored, but production deployment must still back up and inspect its
own data before applying V7.

## Verification performed

- Local-provider owner isolation, read-back and courseware restart rehydration tests pass.
- The FFmpeg courseware media test passes with object-backed source, audio, avatar and video.
- MySQL 5.7 V1-to-V7 migration and metadata CRUD pass in a dedicated disposable schema.
- A real Aliyun OSS test uploaded a random verification object to the configured Beijing
  bucket, read and verified it, deleted it, then verified absence.
- Full Maven regression: 105 tests, 0 failures, 0 errors, 6 environment-gated skips.

## Remaining boundaries

- Local working files still consume disk while media tools run; Phase 6 resource isolation
  and Phase 9 backpressure must bound concurrent disk use.
- Object upload and metadata commit are compensated best-effort, not a distributed
  transaction. Cleanup/reconciliation remains necessary for rare partial failures.
- Courseware's in-process state cache and per-object synchronization are not cross-instance
  concurrency control. Phase 5/10 must move mutation ownership into the durable worker/state
  model before multi-instance courseware execution can be marked verified.
- Live OSS success proves access to the configured bucket, not throughput, availability or
  production capacity.
