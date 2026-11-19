# Design

On a credential-bearing configuration save the value is written to an owner-only secret file and the
configuration persists a reference to it. `src/main/scala/nebflow/core/secrets/SecretsStore.scala`
owns the atomic 0600 write and the `_ref` path rendering;
`src/main/scala/nebflow/core/secrets/ConfigCredentialStore.scala` owns the save/reference split and
`src/main/scala/nebflow/core/secrets/SecretsCatalog.scala` the management surface. Legacy inline
values keep resolving and the user's ability to reveal and copy a value is preserved; agent-facing
projections do not return values. Managed reference reads record the reader and the reference
without the value; a direct shell or filesystem read is explicitly outside this mechanism, since an
in-process resolver cannot audit it — an OS-level audit mechanism would be required. Migration of
existing values runs only against an isolated copy until the owner authorizes production migration.
