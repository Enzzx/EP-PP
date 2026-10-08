# EP02 — Query builder funcional em Clojure

## Integrantes:
Enzo Silva Bellotti <br>
Clóvis de Almeida Pelosini

## Como executar

Requer Java (JDK 11+) e as [ferramentas de linha de comando do Clojure](https://clojure.org/guides/install_clojure).

```bash
clojure -M:run dados/filmes.csv
```

Isso abre o terminal interativo. Os comandos são `QUERY <consulta>` e `EXIT`:

```
$ clojure -M:run dados/filmes.csv
> QUERY onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
Cidade de Deus; 8.60
Ainda Estou Aqui; 8.20
Central do Brasil; 8.00
> QUERY onde genero = "drama" | media duracao
127.40
> EXIT
$
```

Nenhuma entrada derruba o programa: consulta malformada, campo inexistente, comando
desconhecido ou linha em branco são reportados numa linha que começa com `ERRO:` e o
prompt volta.

## A forma da AST

Uma consulta é um **mapa** com dois elementos — é dado puro, sem funções dentro: pode
ser impressa, comparada com `=` e escrita à mão num teste.

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:selecionar [:titulo :nota]]]
 :terminal nil}
```

- `:estagios` — vetor dos estágios **na ordem em que a consulta os lê**. Cada estágio é
  um vetor cujo primeiro elemento é o tipo do nó:

  | Nó | Forma |
  |---|---|
  | filtro | `[:onde <expr>]` |
  | ordenação | `[:ordenar-por <campo> :asc\|:desc]` |
  | corte | `[:limitar <n>]` |
  | projeção | `[:selecionar [<campo> ...]]` |
  | agrupamento | `[:agrupar-por <campo> <agregacao>]` |

- `:terminal` — `nil`, ou o nó do terminal quando a consulta termina em `contar`,
  `soma` ou `media`: `[:contar]`, `[:soma <campo>]` ou `[:media <campo>]`. O terminal é
  mutuamente exclusivo com um `:selecionar`/`:agrupar-por` final (que ficam em
  `:estagios`).

- `<expr>` é a árvore recursiva das expressões booleanas:

  | Nó | Forma |
  |---|---|
  | comparação | `[:= <campo> <literal>]`, e os operadores `:!=` `:<` `:<=` `:>` `:>=` |
  | e | `[:e <expr> <expr>]` |
  | ou | `[:ou <expr> <expr>]` |
  | não | `[:nao <expr>]` |

- `<agregacao>` é `[:contar]`, `[:soma <campo>]` ou `[:media <campo>]`.

- `<campo>` é sempre palavra-chave (`:genero`); `<literal>` é `Long` (inteiro),
  `Double` (decimal) ou `String` (texto).

Exemplos:

```clojure
;; QUERY onde genero = "drama" | limitar 2
{:estagios [[:onde [:= :genero "drama"]]
            [:limitar 2]]
 :terminal nil}

;; QUERY agrupar por genero com contar
{:estagios [[:agrupar-por :genero [:contar]]]
 :terminal nil}

;; QUERY contar
{:estagios []
 :terminal [:contar]}
```

A correção usa esta seção para montar à mão uma AST com um estágio que não existe na
linguagem (por exemplo `[:voar 3]`) e entregá-la ao compilador: o `case` sobre o tipo
do nó **não tem ramo padrão**, então o nó desconhecido faz a execução falhar com um
erro que nomeia o nó.

## Os namespaces

| Namespace | Papel |
|---|---|
| `consultas.main` | Único com I/O e com `doseq`: leitura do arquivo e o laço do terminal, escrito com `loop`/`recur`. |
| `consultas.sintaxe` | Texto → AST: tokenizador e análise sintática recursiva, sem estado. Cada função de análise recebe os tokens e devolve o nó reconhecido com os tokens que sobraram. |
| `consultas.compilador` | Conferência da AST contra o esquema e compilação da AST em funções (`case` sem ramo padrão). A AST é percorrida uma única vez, antes da execução. |
| `consultas.core` | Texto do CSV → registros; o *query builder* (`consulta`, `onde`, `selecionar`, `ordenar-por`, `limitar`, `contar`, `soma`, `media`, `agrupar-por`), `analisar` e `executar`. |

## O builder devolve consultas novas

```clojure
(require '[consultas.core :as q])

(def base   (q/consulta))
(def dramas (q/onde base [:= :genero "drama"]))
(def dois   (q/limitar dramas 2))
(def tres   (q/limitar dramas 3))   ; dramas não foi alterada por dois

;; o analisador do texto produz exatamente o mesmo dado:
(= (q/analisar "onde genero = \"drama\" | limitar 2")
   dois)                            ; => true
```
