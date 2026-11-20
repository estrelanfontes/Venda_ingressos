# Venda de Ingressos (Java 17+)

Menu de console para gerenciar eventos e vender ingressos, com salvamento em arquivo e
simulação de N usuários simultâneos usando locks.

## Como usar

```bash
./compilar.sh                      # javac -> ./out
./executar.sh                      # menu interativo
./executar.sh --simular 50 300     # simulação direta: 50 usuários x 300 operações
```

Os dados ficam em `dados/ingressos.csv` (carregado automaticamente na abertura).

## Estrutura

```
src/ingressos/
  Main.java                          menu de console
  modelo/       Evento, Venda, EventoDados (snapshot imutável), exceções de negócio
  servico/      IngressoService        fachada thread-safe (locks do catálogo)
  persistencia/ ArmazenamentoCsv       salvar/carregar (escrita atômica)
  simulacao/    Simulador              N threads + verificação de invariantes
```

## Concorrência

| Nível | Lock | Protege | Quem usa |
|---|---|---|---|
| Catálogo | `ReentrantReadWriteLock` (justo) | estrutura do mapa de eventos | leitura: buscar, listar, comprar, cancelar, exportar; escrita: adicionar, remover, importar |
| Evento | `ReentrantLock` (um por evento) | estoque e vendas do evento | `reservar`, `cancelar`, `snapshot` |
| Arquivo | `ReentrantLock` | acesso ao arquivo | `salvar`, `carregar` |

Ordem de aquisição sempre **catálogo -> evento**; nunca o inverso (sem deadlock).
O I/O de disco acontece fora dos locks do domínio.
