(ns consultas.core
  "Texto do CSV -> registros; o query builder e a execucao.
  O builder recebe uma consulta e devolve outra, sem alterar a de partida:
  a consulta e dado puro."

  (:require [clojure.string :as str]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

;; ---------------------------------------------------------------------------
;; Query builder: so constrói dado
;; ---------------------------------------------------------------------------

(defn consulta
  "Consulta nova, sem nenhum estagio: devolve todos os registros."
  []
  {:estagios [] :terminal nil})

(defn onde
  "Acrescenta um filtro. expr e um vetor: [:= :campo valor], [:e a b],
  [:ou a b], [:nao a]."
  [c expr]
  (update c :estagios conj [:onde expr]))

(defn selecionar
  "Projeção de campos (vetor de palavras-chave). E o ultimo elemento."
  [c campos]
  (update c :estagios conj [:selecionar (vec campos)]))

(defn ordenar-por
  "Ordenacao estavel. ordem e :asc ou :desc."
  [c campo ordem]
  (update c :estagios conj [:ordenar-por campo ordem]))

(defn limitar [c n]
  (update c :estagios conj [:limitar n]))

(defn contar [c]
  (assoc c :terminal [:contar]))

(defn soma [c campo]
  (assoc c :terminal [:soma campo]))

(defn media [c campo]
  (assoc c :terminal [:media campo]))

(defn agrupar-por
  "Agrupamento por campo com agregacao ([:contar], [:soma c] ou [:media c])."
  [c campo agregacao]
  (update c :estagios conj [:agrupar-por campo agregacao]))

(defn analisar
  "O texto de uma consulta, sem a palavra QUERY, transformado em AST.
  Erro de sintaxe e sinalizado com ex-info."
  [texto]
  (sintaxe/analisar texto))

;; ---------------------------------------------------------------------------
;; Leitura do CSV: primeira linha declara nome:tipo de cada campo
;; ---------------------------------------------------------------------------

(defn- ler-tipo [texto]
  (case texto
    "inteiro" :inteiro
    "decimal" :decimal
    "texto" :texto
    (throw (ex-info (str "tipo desconhecido no cabecalho: " texto)
                    {:tipo texto}))))

(defn- ler-campo-cabecalho [campo]
  (let [indice (str/last-index-of campo ":")]
    (when (nil? indice)
      (throw (ex-info (str "cabecalho sem tipo: " campo) {:campo campo})))
    [(keyword (subs campo 0 indice)) (ler-tipo (subs campo (inc indice)))]))

(defn- converter-valor [texto tipo]
  (case tipo
    :inteiro (Long/parseLong texto)
    :decimal (Double/parseDouble texto)
    :texto texto))

(defn- ler-registro [linha esquema]
  (let [partes (str/split linha #";" -1)]
    (when (not= (count partes) (count esquema))
      (throw (ex-info "linha com numero de campos diferente do cabecalho"
                      {:linha linha})))
    (into {}
          (map (fn [[nome tipo] parte]
                 [nome (converter-valor (str/trim parte) tipo)])
               esquema
               partes))))

(defn ler-csv
  "Texto de um CSV (separador ;, cabecalho nome:tipo) -> dados:
  {:esquema [[campo tipo] ...] :registros [mapa ...]}"
  [texto]
  (let [linhas (remove str/blank? (str/split-lines texto))
        esquema (mapv ler-campo-cabecalho (str/split (first linhas) #";"))
        registros (mapv #(ler-registro % esquema) (rest linhas))]
    {:esquema esquema :registros registros}))

;; ---------------------------------------------------------------------------
;; Execucao: confere contra o esquema, compila uma unica vez e aplica
;; ---------------------------------------------------------------------------

(defn executar
  "Executa a consulta c sobre dados {:esquema ... :registros ...}.
  Devolve a sequencia de registros resultante, o valor do terminal quando
  a consulta tem um, ou {:erros [...]} quando a consulta e invalida."
  [c dados]
  (let [erros (compilador/conferir c (:esquema dados))]
    (if (seq erros)
      {:erros erros}
      (let [pipeline (compilador/compilar-pipeline c)
            registros (pipeline (:registros dados))]
        (if-let [terminal (:terminal c)]
          ((compilador/compilar-terminal terminal) registros)
          registros)))))
