# Unscripted

Things happen in the world while you explore. A wolf pack chases a flock of sheep across a field, foxes, wolves or zombies come to pick at the remains of dead animals, a wandering trader runs from pillagers, a horde of zombies marches toward a village at night, a dust devil spins around you on a dry day, a tornado comes over the hill and does not stop following you. You can step in or keep walking, and what you do changes how it ends.

A director picks what happens, where and when, based on the time, the weather, the biome, what is around you and what happened there before. Everything uses vanilla mobs, blocks, particles and sounds.

**Server-side only.** Install it on the server and players join with an unmodded client. It also works in singleplayer. Bedrock players can join through Geyser and see the same scenes.

## Scenes

- **Wolf hunt.** A pack surrounds a flock and each wolf goes after its own sheep. Hit a wolf and the pack backs off; hit it again and they turn on you. If nobody stops them, they eat and leave.
- **Scavengers.** Where a large animal died, foxes or wolves show up later to sniff around the remains. Where many died, zombies come at night.
- **Trader in trouble.** Pillagers chase a wandering trader and his llamas. Save him and he gives the player who helped most a gift and sells at half price for a while. Hit him and the pillagers come for you.
- **Wandering horde.** At night, three waves of zombies walk to a village from different sides. The bell rings and the villagers hide. Defend the village and you get Hero of the Village and fireworks. Lose villagers and the village is left damaged. Waves grow with more players nearby and with a full moon.
- **Dust devil.** On a dry, clear day, a small whirlwind wanders around you picking up junk and, now and then, something worth chasing. It can lift you a few blocks.
- **Tornado.** In the rain, a tornado shows up in the distance and comes for the nearest player. Running away rarely works. It lifts animals and players and drops them. The fall hurts but never kills. It goes around buildings and, by default, only tears up grass, leaves, flowers and snow.

The wolves bring their own sheep, so farm animals are never hunted. When a scene ends, its mobs walk away and are removed once nobody can see them; sheep you saved stay, and scavenger zombies stay as regular zombies.

## For server owners

- Requires NeoForge for Minecraft 1.21.1.
- Clients do not need the mod. Vanilla 1.21.1 clients and Bedrock clients through Geyser can join.
- Bedrock through Geyser: Geyser now targets newer Java versions, so a 1.21.1 server needs ViaProxy with the Geyser-ViaProxy plugin in front of it. Bedrock has no wolf howl (Bedrock players hear a bark) and no large colored dust, so they see the tornado as a column of smoke; the full funnel is Java only.
- Configuration: `config/unscripted-server.toml` (or `world/serverconfig/unscripted-server.toml` for a single world). Changes apply when the file is saved, no restart needed. You can turn off or weight each scene, change the time between scenes (5 to 15 minutes by default), the horde size, the rewards, what tornadoes may break (`NONE`, `NATURAL` or `ALL`; `ALL` also needs `mobGriefing`), and whether a tornado can start a short rain.
- Limits keep the server fast: a cap on active scenes and scene mobs, and no new scenes while the server tick is slow. On a 30 player test server the mod took about 0.1 ms per tick.
- Logs: the regular log only shows startup, config changes and warnings. Scene details go to `logs/debug.log`.

Commands (operators only):

| Command | What it does |
|---|---|
| `/unscripted status` | What the director sees around you and the scene running |
| `/unscripted run <scene>` | Starts a scene near you: `wolf_hunt`, `scavengers`, `trader_in_trouble`, `wandering_horde`, `dust_devil`, `tornado` |
| `/unscripted pacing fast` | Scenes every 30 to 60 seconds, for testing (`normal` to go back; not saved) |
| `/unscripted perf` | Time per tick taken by the mod |

## Building

`./gradlew build` with Java 21. The jar ends up in `neoforge-1.21.1/build/libs/`.

## License

All rights reserved, with permissions: you can play it, run it on any server, include it in modpacks (also monetized ones, downloading it from CurseForge or Modrinth) and make videos. Reuploading the jar, forks, ports and copying the code into other projects are not allowed. See [LICENSE](LICENSE).

---

# Unscripted (español)

Mientras exploras, el mundo hace cosas. Una manada de lobos persigue un rebaño de ovejas por el campo, zorros, lobos o zombies llegan a los restos de animales muertos, un comerciante ambulante huye de unos saqueadores, una horda de zombies marcha de noche hacia una aldea, un remolino da vueltas a tu alrededor en un día seco, un tornado aparece detrás de la colina y no deja de seguirte. Puedes meterte o seguir de largo, y lo que hagas cambia cómo termina.

Un director decide qué pasa, dónde y cuándo, según la hora, el clima, el bioma, lo que hay alrededor y lo que pasó antes en ese lugar. Todo con mobs, bloques, partículas y sonidos vanilla.

**Solo de servidor.** Se instala en el servidor y los jugadores entran sin mods. También funciona en un jugador. Los jugadores de Bedrock pueden entrar con Geyser y ven las mismas escenas.

## Escenas

- **Lobos de caza.** Una manada rodea un rebaño y cada lobo va por su oveja. Si golpeas a un lobo, la manada se aleja; si lo vuelves a golpear, se lanzan contra ti. Si nadie los detiene, comen y se van.
- **Carroñeros.** Donde murió un animal grande, más tarde llegan zorros o lobos a olfatear los restos. Donde murieron muchos, de noche llegan zombies.
- **Comerciante en problemas.** Unos saqueadores persiguen a un comerciante ambulante y sus llamas. Si lo salvas, le da un regalo a quien más ayudó y vende a mitad de precio un rato. Si lo golpeas, los saqueadores van por ti.
- **Horda errante.** De noche, tres oleadas de zombies caminan hacia una aldea, cada una desde otro lado. La campana suena y los aldeanos se esconden. Si defiendes la aldea, recibes Héroe de la aldea y hay fuegos artificiales. Si mueren aldeanos, la aldea queda dañada. Las oleadas crecen con más jugadores cerca y con luna llena.
- **Remolino.** En un día seco y despejado, un remolino pequeño da vueltas a tu alrededor levantando basura y, a veces, algo que vale la pena perseguir. Puede levantarte unos bloques.
- **Tornado.** Con lluvia, aparece un tornado a lo lejos y va por el jugador más cercano. Huir casi nunca sirve. Levanta animales y jugadores y los suelta. La caída duele pero nunca mata. Rodea las construcciones y, por defecto, solo arranca pasto, hojas, flores y nieve.

Los lobos traen sus propias ovejas: nunca cazan animales de granja. Al terminar una escena, sus mobs se alejan y desaparecen cuando nadie los ve; las ovejas que salvas se quedan, y los zombies carroñeros quedan como zombies comunes.

## Para administradores

- Requiere NeoForge para Minecraft 1.21.1.
- Los clientes no necesitan el mod. Entran clientes vanilla 1.21.1 y clientes Bedrock con Geyser.
- Bedrock con Geyser: Geyser ahora apunta a versiones de Java más nuevas, así que un servidor 1.21.1 necesita ViaProxy con el plugin Geyser-ViaProxy delante. Bedrock no tiene el aullido del lobo (los jugadores de Bedrock oyen un ladrido) ni el polvo de color grande, así que ven el tornado como una columna de humo; el embudo completo es solo de Java.
- Configuración: `config/unscripted-server.toml` (o `world/serverconfig/unscripted-server.toml` para un solo mundo). Los cambios se aplican al guardar el archivo, sin reiniciar. Se puede apagar o dar peso a cada escena, cambiar el tiempo entre escenas (5 a 15 minutos por defecto), el tamaño de la horda, las recompensas, qué puede romper el tornado (`NONE`, `NATURAL` o `ALL`; `ALL` también necesita `mobGriefing`) y si un tornado puede empezar una lluvia corta.
- Topes para que el servidor no se ponga lento: escenas activas y mobs de escenas, y sin escenas nuevas mientras el tick del servidor va lento. En un servidor de prueba con 30 jugadores, el mod usó unos 0,1 ms por tick.
- Registro: el registro normal solo muestra el arranque, los cambios de configuración y los avisos. El detalle de las escenas va a `logs/debug.log`.

Comandos (solo operadores):

| Comando | Qué hace |
|---|---|
| `/unscripted status` | Lo que ve el director a tu alrededor y la escena en curso |
| `/unscripted run <escena>` | Empieza una escena cerca de ti: `wolf_hunt`, `scavengers`, `trader_in_trouble`, `wandering_horde`, `dust_devil`, `tornado` |
| `/unscripted pacing fast` | Escenas cada 30 a 60 segundos, para probar (`normal` para volver; no se guarda) |
| `/unscripted perf` | Tiempo por tick que usa el mod |

## Compilar

`./gradlew build` con Java 21. El jar queda en `neoforge-1.21.1/build/libs/`.

## Licencia

Todos los derechos reservados, con permisos: se puede jugar, usar en cualquier servidor, incluir en modpacks (también monetizados, descargándolo desde CurseForge o Modrinth) y hacer videos. No se permite volver a subir el jar, publicar forks o ports ni copiar el código a otros proyectos. Ver [LICENSE](LICENSE).
