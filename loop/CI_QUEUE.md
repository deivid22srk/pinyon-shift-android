# Fila de CI — runs em andamento e concluídos

| Run | Commit | Disparo | Status | Nota |
|---|---|---|---|---|
| 37139486964 | 8acd180 (base) | push 17:09Z | em andamento | valida a nova base (bump bit-field) |
| 37140676682 | 58ecf9a+ae2b344 (minha branch) | dispatch 17:29Z | em andamento | frio: cache vazio + gate 16KB |
| 37140676682 | 58ecf9a+ae2b344 | dispatch 17:29Z | ✅ VERDE 31min | gate 16KB: 10/10 libs p_align=16384 (antes 0x1000); ccache frio 0/1140, 0.5GB salvo; APK 53M |
| 37142696303 | ba1e332 | push 18:02Z | ❌ FALHOU 10min | javac: FEATURE_VULKAN_VERSION não existe (L3). Correto: FEATURE_VULKAN_HARDWARE_VERSION (API 24). Fix no próximo commit |
| 37150524858 | a80e341 | push | ✅ VERDE 15min | controllerdb + ui.record gate + fonte host |
| 37152590349 | 8ca9619 | push | ❌ FALHOU 2min | guard de contrato: pin rexglue.revision desatualizado (404fa7ee≠f4df688) — funcionou como projetado |
| 37152863464 | 346c642 | push | em andamento | pin corrigido + compilação do SDK (P1+P2+P3) |
| 37152863464 | 346c642 | push | ✅ VERDE ~67min | SDK recompilado (P1+P2+P3 do f4df688); ccache SDK quente a partir de agora |
| 37154598345 | fd56120 | push | ✅ VERDE ~24min | APK com todos os fixes: FMV race (P3), cópia por nível (P2), máscaras (P1), controllerdb, fontes, MTP dirs, environment.txt |
| 37157374544 | 66d7d97 | dispatch 22:07Z | em andamento | fix do crash FMV: RequestPartial→Request no fast path; codegen cache HIT; docs de loop retidos |
| 37157374544 | 66d7d97 | dispatch 22:07Z | ✅ VERDE ~22 min | APK com fix do crash FMV (Request full-page); codegen cache HIT validado |
