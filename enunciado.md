# Query builder funcional em Clojure

Este trabalho constrói, em Clojure, um **motor de consultas** sobre um conjunto de registros carregado de um arquivo CSV. O usuário escreve uma consulta em texto — filtrar, ordenar, projetar, agrupar, agregar — e o motor a transforma primeiro em **dado** e depois numa **função composta**, que é então aplicada aos registros.

O assunto do trabalho é o paradigma funcional, e não o domínio de bancos de dados. A linguagem de consulta é pequena de propósito; o que se avalia é **como** ela é implementada: a consulta representada como dado, a compilação desse dado em funções, a composição dessas funções, a aplicação parcial nos estágios e nenhum estado mutável em todo o caminho.

## Interface de uso

O trabalho entrega um programa que recebe o caminho de um arquivo CSV, carrega os registros e abre um terminal interativo:

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

São dois comandos:

| Comando | Efeito |
|---|---|
| `QUERY <consulta>` | Executa a consulta e imprime o resultado. |
| `EXIT` | Encerra o programa. |

Regras da interface:

- O programa imprime o prompt `> ` e lê um comando por linha. Só `EXIT` encerra; o fim da entrada vinda de um *pipe* equivale a `EXIT`.
- **Nenhuma entrada derruba o programa.** Comando desconhecido, consulta malformada, campo inexistente, linha em branco: o erro é reportado numa linha começando com `ERRO:` e o prompt volta. Nenhuma exceção chega ao usuário como *stack trace*.
- `QUERY` é o único comando com resposta multilinha. Resultado vazio imprime `(vazio)`.

## Os dados

O arquivo CSV usa `;` como separador, não tem aspas nem escape, e a **primeira linha declara o nome e o tipo de cada campo**:

```
titulo:texto;ano:inteiro;genero:texto;nota:decimal;duracao:inteiro
Cidade de Deus;2002;drama;8.6;130
Central do Brasil;1998;drama;8.0;113
O Auto da Compadecida;2000;comedia;8.6;104
Tropa de Elite;2007;acao;8.0;115
Bacurau;2019;suspense;7.4;131
Que Horas Ela Volta;2015;drama;7.7;112
Aquarius;2016;drama;7.5;145
Ainda Estou Aqui;2024;drama;8.2;137
```

- Os tipos são `inteiro`, `decimal` e `texto`. Cada registro é um **mapa** de palavra-chave para valor: `{:titulo "Bacurau" :ano 2019 ...}`.
- Todo registro tem todos os campos preenchidos.
- **O motor não conhece nenhum esquema.** Na correção, o programa é executado sobre um CSV com outros campos e outros tipos. **Nenhum nome de campo pode aparecer no código** — a mesma regra que o EP01 impôs aos prefixos de extensão.
- Toda consulta busca sobre **todos os registros** do arquivo; não há fonte a escolher.

## A linguagem de consulta

```
consulta  := [ elemento [| elemento]* ]
elemento  := estagio | final

estagio   := onde <expr>
           | ordenar por <campo> [desc]
           | limitar <n>

final     := selecionar <campo> [, <campo>]*
           | contar | soma <campo> | media <campo>
           | agrupar por <campo> com <agregacao>

agregacao := contar | soma <campo> | media <campo>

expr      := expr ou expr | expr e expr | nao expr | ( expr ) | <campo> <op> <literal>
op        := = | != | < | <= | > | >=
literal   := inteiro | decimal | "texto"
```

Precedência, da maior para a menor: `nao`, `e`, `ou`. Inteiro e decimal são comparáveis entre si (`nota >= 8` é válido); texto só se compara com texto.

Semântica:

- **Saída de registros**: uma linha por registro, valores separados por `; `, na ordem do `selecionar` ou, sem ele, na ordem do esquema.
- **Números**: inteiro sai como inteiro; decimal sai com duas casas e **ponto** como separador (`8.60`, nunca `8,60`).
- **`QUERY` sem nada** imprime todos os registros, na ordem do arquivo.
- **O elemento final**, quando existe, é o último da consulta: `selecionar`, um terminal ou `agrupar por`. Nada vem depois dele.
- **Ordenação**: estável — registros empatados mantêm a ordem em que vieram. Texto se ordena pela comparação padrão de *strings*.
- **Terminais** imprimem um único valor. `soma` e `media` exigem campo numérico; `media` é decimal. Sem nenhum registro, os três imprimem zero.
- **Consulta inválida**: campo inexistente, comparação entre tipos incompatíveis e `soma` ou `media` sobre campo que não é numérico são erros. A consulta é conferida contra o esquema **antes** de executar, e a inválida é recusada com `ERRO:` sem processar nenhum registro.
- **`agrupar por`** imprime uma linha por grupo, `chave; valor`, em ordem crescente de chave. Sem nenhum registro, imprime `(vazio)`.

Exemplos sobre o arquivo acima:

```
> QUERY agrupar por genero com contar
acao; 1
comedia; 1
drama; 5
suspense; 1
> QUERY onde nota < 7.5 | contar
1
> QUERY onde ano > 2010 | ordenar por ano | limitar 2 | selecionar titulo, ano
Que Horas Ela Volta; 2015
Aquarius; 2016
> QUERY onde diretor = "x"
ERRO: campo inexistente: diretor
> QUERY onde ano = "2002"
ERRO: comparacao entre inteiro e texto no campo ano
```

## O que se pede: o funcional, item a item

### 1. A consulta é dado

O texto da consulta é transformado numa **árvore sintática** (*AST*) feita só de estruturas de dados do Clojure — mapas, vetores e palavras-chave. Uma forma possível:

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:selecionar [:titulo :nota]]]
 :terminal nil}
```

A forma exata é decisão do grupo e vai no `README.md`. O que não é decisão: a AST é **dado puro**, sem funções dentro — pode ser impressa, comparada com `=` e escrita à mão num teste. É o princípio de que **código é dado**, aplicado à consulta.

### 2. As expressões são recursivas, e a análise também

`expr` contém `expr`, e o vetor `[:e a b]` contém outros vetores de expressão. A **análise sintática** é recursiva e sem estado: cada função recebe a sequência de *tokens* e devolve o nó reconhecido junto com os *tokens* que sobraram. Nenhuma variável guarda a posição de leitura.

### 3. Compilar, não interpretar

A AST é percorrida **uma única vez**, antes da execução, e transformada em funções: cada `onde` vira um predicado sobre um registro, e cada estágio vira uma função de sequência em sequência. Durante a execução, **nenhuma inspeção da AST acontece por registro** — o registro passa por funções já montadas.

A compilação despacha cada nó por um **`case` sobre o tipo do nó**, **sem expressão padrão** no fim do `case`. Na correção, uma AST com um tipo de nó que não existe na linguagem é entregue ao compilador, e ele **tem que falhar** com um erro que nomeie o nó — não pode ignorá-lo nem tratá-lo como um nó qualquer.

### 4. A consulta é uma composição

Cada estágio compilado é uma função de uma sequência de registros em outra sequência de registros. A consulta é a **composição** delas, construída com `reduce` sobre os estágios a partir de `identity`, ou com `comp`. A ordem importa: `comp` aplica da direita para a esquerda, e a consulta lê da esquerda para a direita.

### 5. Estágio com argumento é aplicação parcial

`limitar 3` não limita nada quando é compilado: vira uma função que já fixou o `3` e espera a sequência. O mesmo vale para `ordenar por`, `selecionar` e para cada comparação de um `onde`, que fixa o campo e o literal.

### 6. O builder devolve consultas novas

O motor expõe funções — o *query builder* propriamente dito — que **recebem uma consulta e devolvem outra**, sem alterar a de partida. Como a consulta é dado, o builder só constrói dado:

```clojure
(def base    (consulta))
(def dramas  (onde base [:= :genero "drama"]))
(def dois    (limitar dramas 2))
(def tres    (limitar dramas 3))      ; dramas nao foi alterada por dois

(executar dois dados)                 ; => os dois primeiros dramas do arquivo
```

O analisador do texto produz exatamente o mesmo dado que o builder: `(analisar "onde genero = \"drama\" | limitar 2")` e a expressão `dois` acima resultam em ASTs iguais por `=`. Texto, dado e função são três camadas sobre o mesmo modelo. Continuar uma consulta é simplesmente partir do valor dela: `dois` e `tres` continuam `dramas`, e `dramas` não muda.

O contrato do builder, que os testes da correção usam diretamente, vive no *namespace* `consultas.core`:

| Função | Efeito |
|---|---|
| `(consulta)` | Consulta nova, sem nenhum estágio: devolve todos os registros. |
| `(onde c expr)` | Acrescenta um filtro. `expr` é um vetor: `[:= :campo valor]`, `[:e a b]`, `[:ou a b]`, `[:nao a]`; os operadores são `:=`, `:!=`, `:<`, `:<=`, `:>`, `:>=`. |
| `(selecionar c campos)` | `campos` é um vetor de palavras-chave. É o último elemento: estágio acrescentado depois dele torna a consulta inválida. |
| `(ordenar-por c campo ordem)` | `ordem` é `:asc` ou `:desc`. |
| `(limitar c n)` | |
| `(analisar texto)` | O texto de uma consulta, sem a palavra `QUERY`, transformado em AST. Erro de sintaxe é sinalizado com `ex-info`. |
| `(executar c dados)` | `dados` é `{:esquema [[campo tipo] ...] :registros seq}`, com `tipo` em `:inteiro`, `:decimal` ou `:texto`. Devolve a sequência de registros resultante — cada registro um mapa —, o valor do terminal quando a consulta tem um, ou `{:erros [...]}`, com ao menos um motivo, quando a consulta é inválida. |

### 7. Agregar é dobrar

`contar`, `soma` e `media` são escritos com `reduce`. `agrupar por` pode usar `group-by` da biblioteca padrão, mas a agregação dentro de cada grupo é a mesma dobra dos terminais, reaproveitada.

## Restrições de implementação

### Cláusulas de pureza

| Proibido | Onde |
|---|---|
| `atom`, `ref`, `agent`, `volatile!`, `transient`, `set!` e coleções mutáveis de Java | Em todo o projeto |
| `doseq`, `dotimes`, `while` | Em todo o projeto, **exceto** no *namespace* de entrada |
| `eval`, `read-string` | Em todo o projeto |

`loop`/`recur` é recursão, e é permitido em qualquer lugar. O projeto não usa nenhuma biblioteca além do próprio Clojure: analisador sintático, leitor de CSV e motor de consulta são o conteúdo do trabalho.

**O laço do terminal é recursão.** Ele é escrito com `loop`/`recur`: lê uma linha, responde e volta ao começo. Ler o terminal e imprimir são efeitos e ficam no *namespace* de entrada.

`eval` e `read-string` estão fora porque a linguagem de consulta não é Clojure: o texto tem que ser analisado, e não executado.

### Organização do código

```
consultas.main        -> laco do terminal e leitura do arquivo; o unico com I/O e com doseq
consultas.sintaxe     -> texto -> AST
consultas.compilador  -> conferencia da AST contra o esquema; AST -> funcoes (case)
consultas.core        -> texto do CSV -> registros; o builder e executar
```

Os nomes são sugestão, com exceção de `consultas.core`, que os testes importam.

### Execução

O projeto usa `deps.edn`, sem dependências além do próprio Clojure, com o *alias* `:run`: `clojure -M:run <arquivo.csv>` abre o terminal.

## Verificação na correção

- **Um CSV desconhecido**, com outros campos e outros tipos, e um roteiro de consultas sobre ele.
- **Testes do builder**, escritos pela correção contra o contrato da seção 6, incluindo a derivação de duas consultas a partir de uma base e a igualdade entre a AST do texto e a AST do builder.
- **Um nó desconhecido**: uma AST montada segundo a forma descrita no `README.md`, com um estágio que não existe na linguagem, é executada. Tem que falhar nomeando o nó.
- **A bateria** `casos_teste_ep02.txt`, que acompanha este enunciado.
- **Uma busca** pelos símbolos proibidos da tabela de pureza.

## Casos de teste

Acompanham este enunciado o arquivo [`dados/filmes.csv`](dados/filmes.csv), o mesmo dos exemplos, e a bateria [`casos_teste_ep02.txt`](casos_teste_ep02.txt). Cada caso é um comando e a resposta esperada: com o programa aberto sobre `dados/filmes.csv`, o comando é digitado no terminal e a resposta é conferida com a do arquivo.

Nos casos de erro, o texto do motivo é livre, exceto onde a bateria indica que ele tem que citar um campo.

## Documentação

O `README.md` começa pelo nome dos integrantes e traz:

- **Como executar** o programa.
- **A forma da AST**: o dado que representa uma consulta, com um exemplo. A correção usa esta seção para montar à mão a AST do teste do nó desconhecido.
- **Os *namespaces***: o papel de cada um.

Entrega sem `README.md`, ou sem a forma da AST, sofre desconto de 2,0 pontos.

## Entrega

O único entregável é a URL de um repositório público no GitHub, com o `README.md` começando pelo nome completo de todos os integrantes. Grupos de 1 a 3 integrantes, e uma entrega por grupo.

```
repositorio/
├── deps.edn
├── README.md
├── src/consultas/
│   ├── main.clj
│   ├── core.clj
│   └── ...                      # os namespaces acima
├── dados/
│   └── filmes.csv
└── casos_teste_ep02.txt
```

Este trabalho deve seguir a [Política de uso de ferramentas generativas de IA](https://crivelaro.notion.site/Pol-tica-de-uso-de-ferramentas-generativas-de-IA-1b53bb4e12a54b4aa06eaa02e62192f4?pvs=74) e a [Política antiplágio](https://crivelaro.notion.site/Pol-tica-antipl-gio-5187d7b1ab514bfb8424ac0fcfb59dba?pvs=74).

## Anexo

- [**Anexo — Clojure para este trabalho**](anexo-clojure.md): o que o trabalho usa de Clojure além do que foi visto em aula — mapas e palavras-chave como dado, `comp` e `partial`, `reduce`, `case`, `ex-info`, `loop`/`recur` e `deps.edn`.

## Referências

- TATE, Bruce A. **Seven Languages in Seven Weeks.** Raleigh: The Pragmatic Bookshelf, 2010. Capítulo 7 (Clojure).
- [clojure.core/case](https://clojuredocs.org/clojure.core/case)
- [Clojure - Data Structures](https://clojure.org/reference/data_structures) — mapas, vetores e palavras-chave imutáveis.
- [Clojure - Deps and CLI](https://clojure.org/guides/deps_and_cli) — `deps.edn` e *aliases*.