# SeedShield

**Cryptographic structure seed protection for Paper/Spigot servers.**

**[中文](README.md) | English**

SeedShield prevents seed cracking tools (chunkbase, SeedCrackerX, Structurecracker) from determining structure locations by replacing each structure type's placement salt with an irreversible SHA-256 derived value.

**This is the first Paper/Spigot plugin to provide cryptographic structure seed protection.** Previously, this level of protection was only available through Fabric mods or custom server forks.

## How It Works

Minecraft determines structure positions using this formula:

```
position = f(worldSeed, regionCoords, salt)
```

The `salt` is a hardcoded integer per structure type. Tools like chunkbase know these default salts, so knowing the world seed = knowing all structure locations.

SeedShield replaces each salt with:

```
salt = SHA-256(secretKey + ":" + worldSeed + ":" + structureType)[0..4]
```

- **Per-structure isolation**: Each structure type gets a unique cryptographic salt. Cracking one type's salt reveals nothing about others.
- **Secret key protection**: Without the 256-bit key (stored in `config.yml`), salts cannot be reversed.
- **Stronghold protection**: Also modifies `concentricRingsSeed` and recalculates ring positions.

## Comparison with Existing Solutions

| Solution | Platform | Modifies Structure Positions | Crypto Protection | Per-Structure Isolation | Stronghold Protection |
|----------|----------|:---:|:---:|:---:|:---:|
| **SeedShield** | **Paper/Spigot plugin** | **✅** | **✅ SHA-256** | **✅** | **✅** |
| [SeedGuard](https://github.com/DrexHD/SeedGuard) | Fabric mod | ✅ | ❌ Random | ✅ | ✅ |
| [SecureSeed](https://github.com/Earthcomputer/SecureSeed) | Fabric mod (1.16.5, abandoned) | ✅ | ✅ BLAKE2 | ✅ | ✅ |
| [AntiSeedCracker](https://github.com/akshualy/AntiSeedCracker) | Spigot plugin | ❌ | ❌ | ❌ | ❌ |
| Leaf `secure-seed` | Server fork | ✅ | ✅ 1024-bit | ✅ | ✅ |
| spigot.yml seeds | Built-in | ✅ | ❌ Plain int | ❌ | ⚠️ |

## Installation

1. Download `SeedShield-1.0.0.jar` from [Releases](https://github.com/7yunluo/SeedShield/releases)
2. Place it in your server's `plugins/` folder
3. **Delete the region files** of worlds you want to protect (structures must regenerate)
4. Restart the server
5. Edit `plugins/SeedShield/config.yml` to configure which worlds to protect

> **Important**: SeedShield only affects newly generated chunks. Existing structures in already-generated chunks will not change positions.

## Configuration

```yaml
# Auto-generated 256-bit secret key. DO NOT SHARE.
secret-key: "a1b2c3d4..."

# Worlds to protect
enabled-worlds:
  - world
  - survival
```

## Requirements

- Paper 1.20.5+ (or forks: Leaves, Purpur, Folia, etc.)
- Java 17+

> **Note**: Versions 1.20.4 and below are not supported. Paper switched to Mojang mappings starting from 1.20.5, which this plugin relies on for reflection.

## Security Analysis

| Attack Vector | Protection Level |
|---------------|:---:|
| chunkbase / online seed maps | ✅ Fully defeated |
| SeedCrackerX client mod | ✅ Defeated (combine with FakeSeed for hashed seed) |
| Brute-force single structure salt (2³² attempts) | ⚠️ Possible per-type, but each type must be cracked independently |
| Reverse secret key from salt | ✅ Infeasible (SHA-256 preimage resistance) |
| Cross-structure salt derivation | ✅ Impossible without key |

## Building from Source

```bash
git clone https://github.com/7yunluo/SeedShield.git
cd SeedShield
mvn package
```

The JAR will be at `target/SeedShield-1.0.0.jar`.

## License

MIT License
