(ns consultas.compilador
  "Conferencia da AST contra o esquema e compilacao da AST em funcoes.
  O despacho de cada no e feito com case sobre o tipo do no, sem ramo
  padrao: um no que nao existe na linguagem faz o case falhar nomeando-o.
  A compilacao acontece uma unica vez, antes da execucao; durante a
  execucao o registro passa por funcoes ja montadas."

  (:require [clojure.string :as str]))

(defn- nome-campo [c]
  (if (keyword? c) (name c) (pr-str c)))

(defn- tipo-do-literal [v]
  (cond
    (integer? v) :inteiro
    (number? v) :decimal
    (string? v) :texto
    :else :desconhecido))

(defn- tipos-compatíveis? [t-campo t-literal]
  (if (= t-campo :texto)
    (= t-literal :texto)
    (and (not= t-literal :texto)
         (not= t-literal :desconhecido)
         (not= t-campo :desconhecido))))

(defn- tipo-numerico? [t]
  (or (= t :inteiro) (= t :decimal)))

;; ---------------------------------------------------------------------------
;; Conferencia: AST + esquema -> vetor de motivos (vazio quando valida)
;; ---------------------------------------------------------------------------

(defn conferir-expr [expr tipos]
  (let [[op & args] expr]
    (try
      (case op
        (:e :ou) (concat (conferir-expr (first args) tipos)
                         (conferir-expr (second args) tipos))

        :nao (conferir-expr (first args) tipos)

        (:= :!= :< :<= :> :>=)
        (let [[campo literal] args
              t-campo (get tipos campo)]
          (cond
            (nil? t-campo)
            [(str "campo inexistente: " (nome-campo campo))]

            (not (tipos-compatíveis? t-campo (tipo-do-literal literal)))
            [(str "comparacao entre " (name t-campo)
                  " e " (name (tipo-do-literal literal))
                  " no campo " (nome-campo campo))]

            :else [])))
      (catch IllegalArgumentException _
        (throw (ex-info (str "expressao desconhecida: " (pr-str expr))
                        {:no expr}))))))

(defn conferir-agregacao [no tipos]
  (let [[tipo & args] no]
    (try
      (case tipo
        :contar []

        :soma (let [campo (first args)]
                (cond
                  (nil? (get tipos campo))
                  [(str "campo inexistente: " (nome-campo campo))]

                  (not (tipo-numerico? (get tipos campo)))
                  [(str "soma exige campo numerico: " (nome-campo campo))]

                  :else []))

        :media (let [campo (first args)]
                 (cond
                   (nil? (get tipos campo))
                   [(str "campo inexistente: " (nome-campo campo))]

                   (not (tipo-numerico? (get tipos campo)))
                   [(str "media exige campo numerico: " (nome-campo campo))]

                   :else [])))
      (catch IllegalArgumentException _
        (throw (ex-info (str "agregacao desconhecida: " (pr-str no))
                        {:no no}))))))

(defn conferir-estagio [no tipos]
  (let [[tipo & args] no]
    (case tipo
      :onde (vec (conferir-expr (first args) tipos))

      :ordenar-por (let [campo (first args)]
                     (if (contains? tipos campo)
                       []
                       [(str "campo inexistente: " (nome-campo campo))]))

      :limitar (let [n (first args)]
                 (if (and (integer? n) (not (neg? n)))
                   []
                   ["limitar exige um inteiro nao negativo"]))

      :selecionar (let [campos (first args)]
                    (vec (for [campo campos :when (not (contains? tipos campo))]
                           (str "campo inexistente: " (nome-campo campo)))))

      :agrupar-por (let [[campo agregacao] args]
                     (vec (concat
                           (if (contains? tipos campo)
                             []
                             [(str "campo inexistente: " (nome-campo campo))])
                           (conferir-agregacao agregacao tipos)))))))

(defn conferir-terminal [no tipos]
  (if (nil? no)
    []
    (conferir-agregacao no tipos)))

(defn conferir-estrutura [{:keys [estagios terminal]}]
  (let [indice-final (first (keep-indexed (fn [i [tipo]]
                                            (when (#{:selecionar :agrupar-por} tipo) i))
                                          estagios))]
    (cond
      (not (vector? estagios))
      ["consulta sem o vetor :estagios"]

      (and indice-final (not= indice-final (dec (count estagios))))
      [(if (= :agrupar-por (first (nth estagios indice-final)))
         "agrupar tem que ser o ultimo"
         "selecionar tem que ser o ultimo")]

      (and terminal indice-final)
      ["a consulta nao pode terminar em selecionar/agrupar e em terminal ao mesmo tempo"]

      :else [])))

(defn conferir
  "Confere a AST contra o esquema antes de executar.
  Devolve um vetor de motivos; vazio quando a consulta e valida."
  [ast esquema]
  (let [tipos (into {} esquema)]
    (vec (concat (conferir-estrutura ast)
                 (mapcat #(conferir-estagio % tipos) (:estagios ast))
                 (conferir-terminal (:terminal ast) tipos)))))

;; ---------------------------------------------------------------------------
;; Compilacao: AST -> funcoes. Case sem ramo padrao em cada despacho.
;; ---------------------------------------------------------------------------

(defn compilar-comparacao [op campo literal]
  (case op
    := (let [f (fn [valor]
                 (if (and (number? valor) (number? literal))
                   (== valor literal)
                   (= valor literal)))]
         (fn [reg] (f (get reg campo))))

    :!= (let [igual? (compilar-comparacao := campo literal)]
          (fn [reg] (not (igual? reg))))

    :< (fn [reg] (< (get reg campo) literal))
    :<= (fn [reg] (<= (get reg campo) literal))
    :> (fn [reg] (> (get reg campo) literal))
    :>= (fn [reg] (>= (get reg campo) literal))))

(defn compilar-expr [expr]
  (let [[op & args] expr]
    (try
      (case op
        (:e :ou) (let [[esq dir] args
                       f-esq (compilar-expr esq)
                       f-dir (compilar-expr dir)]
                   (if (= op :e)
                     (fn [reg] (and (f-esq reg) (f-dir reg)))
                     (fn [reg] (or (f-esq reg) (f-dir reg)))))

        :nao (let [f (compilar-expr (first args))]
               (fn [reg] (not (f reg))))

        (:= :!= :< :<= :> :>=)
        (let [[campo literal] args]
          (compilar-comparacao op campo literal)))
      (catch IllegalArgumentException _
        (throw (ex-info (str "expressao desconhecida: " (pr-str expr))
                        {:no expr}))))))

(defn compilar-agregacao [no]
  (let [[tipo & args] no]
    (case tipo
      :contar (fn [regs] (reduce (fn [acc _] (inc acc)) 0 regs))

      :soma (let [campo (first args)]
              (fn [regs] (reduce (fn [acc reg] (+ acc (get reg campo))) 0 regs)))

      :media (let [campo (first args)]
               (fn [regs]
                 (let [[soma n] (reduce (fn [[s n] reg]
                                          [(+ s (get reg campo)) (inc n)])
                                        [0 0]
                                        regs)]
                   (if (zero? n)
                     0
                     (/ (double soma) n))))))))

(defn compilar-estagio [no]
  (let [[tipo & args] no]
    (try
      (case tipo
        :onde (let [predicado (compilar-expr (first args))]
                (partial filter predicado))

        :ordenar-por (let [[campo ordem] args
                           comparar (if (= ordem :desc)
                                      (fn [a b] (compare b a))
                                      compare)]
                       (partial sort-by campo comparar))

        :limitar (let [n (first args)]
                   (partial take n))

        :selecionar (let [campos (first args)]
                      (partial map (fn [reg] (select-keys reg campos))))

        :agrupar-por (let [[campo agregacao] args
                           agregar (compilar-agregacao agregacao)]
                       (fn [regs]
                         (->> (group-by campo regs)
                              (sort-by key)
                              (mapv (fn [[k grupo]]
                                      {:chave k :valor (agregar grupo)}))))))
      (catch IllegalArgumentException _
        (throw (ex-info (str "estagio desconhecido: " (pr-str no))
                        {:no no}))))))

(defn compilar-terminal [no]
  (try
    (case (first no)
      (:contar :soma :media) (compilar-agregacao no))
    (catch IllegalArgumentException _
      (throw (ex-info (str "terminal desconhecido: " (pr-str no))
                      {:no no})))))

(defn compilar-pipeline
  "Compila os estagios da consulta em uma unica funcao de sequencia em
  sequencia, construida com reduce sobre identity. A ordem importa: a
  consulta le da esquerda para a direita."
  [{:keys [estagios]}]
  (reduce (fn [funcao no] (comp (compilar-estagio no) funcao))
          identity
          estagios))
