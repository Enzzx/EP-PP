# Anexo — Clojure para este trabalho

Este anexo acompanha o enunciado do EP02. Ele retoma o que o trabalho usa de Clojure e acrescenta o que ainda não apareceu em aula: o despacho com `case`, `ex-info`, o laço com `loop`/`recur` e `deps.edn`. Cada seção mostra a construção e o ponto do enunciado em que ela é usada.

Todos os trechos foram executados em Clojure 1.12; o comentário `;; =>` mostra o valor obtido.

---

## 1. Mapas e palavras-chave: o registro e a consulta como dado

Uma **palavra-chave** (*keyword*) é um identificador que vale a si mesmo, escrito com `:` na frente. Usada como função, ela busca a si própria num mapa:

```clojure
(def filme {:titulo "Bacurau" :ano 2019})

(:ano filme)              ;; => 2019
(get filme :ano)          ;; => 2019     (o mesmo, com get)
```

Mapas e vetores são **imutáveis**. `assoc`, `update` e `conj` devolvem uma coleção nova, e a original continua a mesma:

```clojure
(assoc filme :nota 7.4)   ;; => {:titulo "Bacurau", :ano 2019, :nota 7.4}
filme                     ;; => {:titulo "Bacurau", :ano 2019}

(update {:estagios []} :estagios conj [:limitar 2])
;; => {:estagios [[:limitar 2]]}
```

O segundo exemplo é o *builder* inteiro do enunciado (requisito 6): acrescentar um estágio é devolver uma consulta nova com um vetor a mais. A cópia não é integral — as coleções do Clojure são **persistentes** e compartilham com a versão anterior tudo o que não mudou.

Como a AST é feita só dessas estruturas, ela se compara com `=`, se imprime e se escreve à mão num teste:

```clojure
(= [:> :n 1000] [:> :n 1000])   ;; => true
```

É o que permite à correção comparar a AST do texto com a AST do *builder* (requisito 6). Uma função dentro da AST quebraria essa igualdade: duas funções só são iguais se forem o mesmo objeto.

## 2. Composição e aplicação parcial

`partial` fixa os primeiros argumentos de uma função e devolve outra, que espera o resto. `comp` compõe funções:

```clojure
(def mais-um (partial + 1))
(mais-um 41)                                   ;; => 42

((comp count (partial filter even?)) [1 2 3 4])   ;; => 2
```

`comp` aplica **da direita para a esquerda**: `(comp f g)` é `f` depois de `g`. Uma consulta lê da esquerda para a direita, e por isso compor os estágios na ordem do texto exige inverter a ordem. Uma forma é dobrar a lista de estágios a partir de `identity`:

```clojure
((comp inc (partial * 10)) 1)                                    ;; => 11
((reduce (fn [f g] (comp g f)) identity [inc (partial * 10)]) 1) ;; => 20
```

No primeiro, `* 10` roda antes de `inc`; no segundo, `inc` roda antes, como num pipeline. Os requisitos 4 e 5 do enunciado são exatamente isso, com estágios no lugar de `inc`.

## 3. Dobrar com `reduce`

`reduce` percorre uma coleção carregando um acumulador. Para devolver mais de um valor — a soma e a contagem de uma média, por exemplo —, o acumulador é um vetor:

```clojure
(reduce + 0 [1 2 3])                                         ;; => 6
(reduce (fn [[soma n] v] [(+ soma v) (inc n)]) [0 0] [4 6])  ;; => [10 2]
```

A desestruturação `[[soma n] v]` nos parâmetros separa o acumulador nas suas partes. Nenhuma variável muda: cada passo devolve um acumulador novo.

`group-by` devolve um mapa da chave para o vetor de registros do grupo, e serve ao `agrupar por` (requisito 7):

```clojure
(group-by :g [{:g :a} {:g :b} {:g :a}])
;; => {:a [{:g :a} {:g :a}], :b [{:g :b}]}
```

## 4. Despacho com `case`

`case` compara um valor com uma lista de constantes e executa o ramo da primeira que for igual. Um ramo pode listar várias constantes entre parênteses:

```clojure
(defn tipo-de [op]
  (case op
    (:e :ou :nao)                  :logico
    (:= :!= :< :> :<= :>=)         :comparacao))

(tipo-de :ou)    ;; => :logico
(tipo-de :>=)    ;; => :comparacao
```

Uma expressão solta no fim do `case`, sem constante antes dela, é o **ramo padrão**: vale para qualquer valor que não casou com os outros. **Sem ramo padrão**, um valor que não casa com nenhuma constante faz o `case` falhar com uma mensagem que nomeia o valor:

```clojure
(case :x :a 1 :b 2 :outro)   ;; => :outro      (com ramo padrao)
(case :x :a 1 :b 2)          ;; IllegalArgumentException: No matching clause: :x
```

Para a AST do enunciado, o valor despachado é o tipo do nó — o primeiro elemento do vetor `[:onde ...]`, `[:limitar 3]`. A desestruturação `[[tipo arg]]` nos parâmetros separa o tipo dos argumentos do nó, e cada ramo devolve a função do estágio:

```clojure
(defn estagio [[tipo arg]]
  (case tipo
    :limitar    (partial take arg)
    :selecionar (partial map #(select-keys % arg))))

((estagio [:limitar 3]) [10 20 30 40])                          ;; => (10 20 30)
((estagio [:selecionar [:titulo]]) [{:titulo "Bacurau" :ano 2019}])
;; => ({:titulo "Bacurau"})
(estagio [:voar 3])
;; IllegalArgumentException: No matching clause: :voar
```

A chamada `(estagio [:limitar 3])` não limita nada: devolve uma função que já fixou o `3` e espera a sequência — a compilação do requisito 3 e a aplicação parcial do requisito 5 na mesma linha. Sem ramo padrão, um nó desconhecido falha com uma mensagem que **nomeia o nó**, que é o comportamento cobrado no requisito 3.

O `case` acontece **uma vez por nó**, quando a consulta é compilada, e não uma vez por registro: o que passa pelos registros é a função que ele devolveu.

## 5. `ex-info`: erro com dado

`ex-info` cria uma exceção que carrega um mapa. `ex-message` e `ex-data` a desmontam:

```clojure
(try
  (throw (ex-info "estagio desconhecido: voar" {:tipo :sintaxe}))
  (catch clojure.lang.ExceptionInfo e
    [(ex-message e) (ex-data e)]))
;; => ["estagio desconhecido: voar" {:tipo :sintaxe}]
```

É a forma indicada para o erro de sintaxe: o contrato do enunciado pede que `analisar` o sinalize com `ex-info`, e a interface o converte numa linha `ERRO:` sem deixar a exceção chegar ao usuário.

## 6. Laço sem atribuição: `loop` e `recur`

`loop` abre um laço com variáveis iniciais, e `recur` volta ao começo com valores novos. Não há atribuição: cada volta é uma chamada com outros argumentos, e `recur` não empilha — reaproveita o quadro da chamada atual.

```clojure
(loop [total 0
       [linha & resto] ["QUERY a" "" "QUERY b"]]
  (if linha
    (recur (if (= linha "") total (inc total)) resto)
    total))
;; => 2
```

O laço do terminal do enunciado tem essa forma, com `read-line` no lugar da lista: lê uma linha, responde e chama `recur`. O que precisa passar de uma volta para a outra passa como argumento; não há `atom` porque não há nada a guardar fora da chamada.

## 7. Ordenação e formatação

`sort-by` recebe a função que extrai a chave e, opcionalmente, um comparador. A ordenação é **estável** — elementos empatados mantêm a ordem de entrada —, e a ordem decrescente se obtém invertendo o comparador:

```clojure
(sort-by :n #(compare %2 %1) [{:n 1} {:n 3} {:n 2}])
;; => ({:n 3} {:n 2} {:n 1})
```

Para formatar decimais com ponto em qualquer máquina, a localidade tem que ser fixada — `format` usa a localidade do sistema, e numa máquina configurada em português imprime `8,60`:

```clojure
(String/format java.util.Locale/ROOT "%.2f" (to-array [8.6]))   ;; => "8.60"
```

## 8. `deps.edn`

O projeto é descrito por um `deps.edn` na raiz. `:paths` diz onde está o código, e cada *alias* em `:aliases` define o que executar:

```clojure
{:paths ["src"]
 :aliases {:run {:main-opts ["-m" "consultas.main"]}}}
```

`clojure -M:run dados/filmes.csv` chama a função `-main` do *namespace* `consultas.main`, com o caminho como argumento. O *namespace* de `src/consultas/main.clj` é `consultas.main`; o de `src/consultas/core.clj`, `consultas.core`. Hífen no nome do *namespace* vira sublinhado no nome do arquivo.

A correção roda os testes dela acrescentando um diretório ao `:paths` na linha de comando; o grupo não precisa preparar nada além do *alias* `:run` e do *namespace* `consultas.core`.

---

## Fecho

Imutabilidade, funções de primeira classe e código como dado são o estado natural do Clojure, e não uma disciplina imposta por proibições. O que a linguagem deixa ao programador é a verificação: um nó que nenhum ramo do `case` trata só aparece em tempo de execução — e é por isso que o enunciado o testa com dados e nós que o grupo nunca viu.