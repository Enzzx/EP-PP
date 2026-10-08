(ns verificar
  "Verifica a entrega contra o enunciado:
  1. bateria casos_teste_ep02.txt (programa real, via subprocesso)
  2. contrato do builder, igualdade texto/builder, no desconhecido
  3. busca de simbolos proibidos"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [consultas.core :as core])
  (:import (java.lang ProcessBuilder ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def raiz (io/file (System/getProperty "user.dir")))

;; ---------------------------------------------------------------------------
;; 1. Parsing da bateria
;; ---------------------------------------------------------------------------

(defn parse-casos [texto]
  (let [linhas (str/split-lines texto)]
    (loop [ls linhas
           casos []
           cmd-pendente nil
           exp-atual nil]
      (let [fechar (fn [casos exp-atual cmd-pendente]
                     (if exp-atual
                       [(conj casos exp-atual) nil nil]
                       [casos nil cmd-pendente]))]
        (if (empty? ls)
          (let [casos (if exp-atual (conj casos exp-atual) casos)]
            casos)
          (let [linha (first ls)
                resto (rest ls)
                trimmed (str/trim linha)]
            (cond
              (str/starts-with? trimmed "#")
              (recur resto casos cmd-pendente exp-atual)

              (str/includes? linha "->")
              (let [[antes depois] (str/split linha #"->" 2)
                    antes (str/trim antes)
                    depois (str/trim depois)]
                (cond
                  (not (str/blank? antes))
                  (recur resto
                         (if exp-atual (conj casos exp-atual) casos)
                         nil
                         {:cmd antes :exp [depois]})

                  cmd-pendente
                  (recur resto casos nil {:cmd cmd-pendente :exp [depois]})

                  :else
                  (recur resto
                         (if exp-atual (conj casos exp-atual) casos)
                         nil
                         {:cmd "" :exp [depois]})))

              (str/blank? linha)
              (recur resto
                     (if exp-atual (conj casos exp-atual) casos)
                     nil
                     nil)

              (Character/isWhitespace (.charAt linha 0))
              (if exp-atual
                (recur resto casos cmd-pendente
                       (update exp-atual :exp conj trimmed))
                (recur resto casos cmd-pendente exp-atual))

              :else
              (recur resto
                     (if exp-atual (conj casos exp-atual) casos)
                     trimmed
                     nil))))))))

;; ---------------------------------------------------------------------------
;; 2. Subprocesso do programa real
;; ---------------------------------------------------------------------------

(defn rodar-programa [comandos]
  (let [cp (System/getProperty "java.class.path")
        arquivo-in (java.io.File/createTempFile "ep02-in" ".txt")
        _ (spit arquivo-in (str (str/join "\n" comandos) "\n"))
        pb (doto (ProcessBuilder. ["java" "-classpath" cp
                                   "clojure.main" "-m" "consultas.main"
                                   "dados/filmes.csv"])
             (.directory raiz)
             (.redirectInput arquivo-in)
             (.redirectError ProcessBuilder$Redirect/INHERIT))
        p (.start pb)
        ;; le com timeout para nao travar para sempre
        leitor (future (slurp (.getInputStream p)))
        terminou (.waitFor p 30 TimeUnit/SECONDS)
        saida (if terminou @leitor (deref leitor 1000 ::timeout))
        codigo (if terminou (.exitValue p) ::timeout)]
    (when-not terminou (.destroyForcibly p))
    (.delete arquivo-in)
    {:saida (if (= saida ::timeout) "" saida)
     :codigo codigo
     :timeout (not terminou)}))

(defn normalizar-saida [saida]
  (->> (str/split-lines saida)
       (map (fn [linha]
              (cond
                (str/starts-with? linha "> ") (subs linha 2)
                (= linha ">") ""
                :else linha)))
       (drop-while str/blank?)
       (reverse)
       (drop-while str/blank?)
       (reverse)
       (vec)))

;; ---------------------------------------------------------------------------
;; 3. Conferencia da bateria
;; ---------------------------------------------------------------------------

(defn caso-encerra? [caso]
  (let [exp (:exp caso)]
    (and (= 1 (count exp)) (str/starts-with? (first exp) "encerra"))))

(defn conferir-bateria [casos]
  (let [entradas (mapv (fn [caso] (if (str/blank? (:cmd caso)) "" (:cmd caso))) casos)
        entradas (if (= "EXIT" (last entradas)) entradas (conj entradas "EXIT"))
        _ (spit (io/file raiz "testes/entrada_debug.txt") (str (str/join "\n" entradas) "\n"))
        {:keys [saidas]} (let [r (rodar-programa entradas)]
                           {:saidas (normalizar-saida (:saida r))})
        esperadas (into []
                        (comp (remove caso-encerra?)
                              (mapcat (fn [caso] (:exp caso))))
                        casos)
        pares (map vector esperadas
                   (concat saidas (repeat nil)))
        falhas (into []
                     (comp (remove (fn [[esp _]] (nil? esp)))
                           (map-indexed
                            (fn [i [esp ob]]
                              (let [ok (if (str/starts-with? esp "ERRO")
                                         (and (some? ob)
                                              (str/starts-with? ob "ERRO:")
                                              (if-let [[_ campo] (re-find #"cita\s+([\w.-]+)" esp)]
                                                (str/includes? (or ob "") campo)
                                                true))
                                         (= esp ob))]
                                (when-not ok
                                  {:indice i :esperado esp :obtido ob})))))
                     pares)
        #_sobras #_(drop (count esperadas) saidas)]
    {:total (count esperadas)
     :falhas (vec (remove nil? falhas))
     :sobras (vec (drop (count esperadas) saidas))}))

;; ---------------------------------------------------------------------------
;; 4. Testes do builder / AST / no desconhecido
;; ---------------------------------------------------------------------------

(defn rodar-testes-api [dados]
  (let [falhas (atom [])]
    ;; apenas neste script de teste o atom e usado; o projeto nao o usa
    (let [verificar (fn [nome cond? detalhe]
                      (when-not cond?
                        (swap! falhas conj {:teste nome :detalhe detalhe})))]
      ;; builder deriva consultas sem alterar a base
      (let [base (core/consulta)
            dramas (core/onde base [:= :genero "drama"])
            dois (core/limitar dramas 2)
            tres (core/limitar dramas 3)]
        (verificar "builder-imutavel"
                   (and (= (count (:estagios dramas)) 1)
                        (= (count (:estagios dois)) 2)
                        (= (count (:estagios tres)) 2)
                        (not= dois tres))
                   {:base base :dois dois :tres tres})
        (let [res-dois (core/executar dois dados)
              res-tres (core/executar tres dados)]
          (verificar "builder-executar"
                     (and (= 2 (count res-dois))
                          (= 3 (count res-tres))
                          (= "drama" (:genero (first res-dois))))
                     {:dois res-dois :tres res-tres})))

      ;; igualdade entre AST do texto e do builder
      (verificar "ast-igualdade"
                 (= (core/analisar "onde genero = \"drama\" | limitar 2")
                    (core/limitar (core/onde (core/consulta) [:= :genero "drama"]) 2))
                 {:texto (core/analisar "onde genero = \"drama\" | limitar 2")
                  :builder (core/limitar (core/onde (core/consulta) [:= :genero "drama"]) 2)})

      (verificar "ast-igualdade2"
                 (= (core/analisar "onde genero = \"drama\" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota")
                    (-> (core/consulta)
                        (core/onde [:e [:= :genero "drama"] [:>= :nota 8]])
                        (core/ordenar-por :nota :desc)
                        (core/selecionar [:titulo :nota])))
                 {:texto (core/analisar "onde genero = \"drama\" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota")
                  :builder (-> (core/consulta)
                               (core/onde [:e [:= :genero "drama"] [:>= :nota 8]])
                               (core/ordenar-por :nota :desc)
                               (core/selecionar [:titulo :nota]))})

      ;; no desconhecido: AST montada a mao com estagio que nao existe
      (let [ast-mao {:estagios [[:voar 3]] :terminal nil}
            resultado (try
                        (core/executar ast-mao dados)
                        (catch Exception e
                          {:excecao (ex-message e)}))]
        (verificar "no-desconhecido"
                   (and (map? resultado)
                        (contains? resultado :excecao)
                        (str/includes? (:excecao resultado) "voar"))
                   {:resultado resultado}))

      ;; terminais e agregacoes
      (verificar "contar" (= 8 (core/executar (core/analisar "contar") dados)) nil)
      (verificar "soma-duracao" (= 987 (core/executar (core/analisar "soma duracao") dados)) nil)
      (verificar "media-nota"
                 (< (Math/abs (- 8.0 (core/executar (core/analisar "media nota") dados))) 1e-9)
                 nil)
      (verificar "vazio-erros"
                 (let [r (core/executar (core/analisar "onde diretor = \"x\"") dados)]
                   (and (map? r) (seq (:erros r))
                        (str/includes? (first (:erros r)) "diretor")))
                 nil))
    @falhas))

;; ---------------------------------------------------------------------------
;; 5. Busca de simbolos proibidos
;; ---------------------------------------------------------------------------

(def proibidos
  [#"\batom\b" #"\bref\b" #"\bagent\b" #"volatile!" #"transient"
   #"\bset!\b" #"\bdoseq\b" #"\bdotimes\b" #"\bwhile\b"
   #"\beval\b" #"read-string"])

(defn arquivos-fonte []
  (->> (file-seq (io/file raiz "src"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))))

(defn testar-pureza []
  (let [arquivos (arquivos-fonte)
        proibidos-main (set (map str [#"\bdoseq\b" #"\bdotimes\b" #"\bwhile\b"]))
        violacoes
        (for [arq arquivos
              :let [texto (slurp arq)
                    nome (.getName arq)
                    eh-main? (= nome "main.clj")]
              padrao proibidos
              :when (not (and eh-main? (contains? proibidos-main (str padrao))))
              :let [linhas (str/split-lines texto)
                    atingidas (for [[i linha] (map-indexed vector linhas)
                                    :when (re-find padrao linha)]
                                {:linha (inc i) :texto (str/trim linha)})]
              :when (seq atingidas)]
          {:arquivo nome :padrao (str padrao) :atingidas atingidas})]
    (vec violacoes)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [log! (fn [msg]
               (println msg)
               (flush)
               (spit (io/file raiz "testes progresso.txt") (str msg "\n") :append true))]
    (log! "iniciando")
    (let [dados (core/ler-csv (slurp (io/file raiz "dados/filmes.csv")))
          texto-casos (slurp (io/file raiz "casos_teste_ep02.txt"))
          casos (parse-casos texto-casos)
          _ (log! (str "Casos lidos: " (count casos)))
          bateria (do (log! "rodando bateria...") (conferir-bateria casos))
          _ (log! (str "bateria ok, falhas=" (count (:falhas bateria))))
          _ (spit (io/file raiz "testes/falhas_bateria.edn")
                  (pr-str {:falhas (:falhas bateria)
                           :sobras (:sobras bateria)
                           :total (:total bateria)}))
          api (try
                (do (log! "rodando api...") (rodar-testes-api dados))
                (catch Exception e
                  (log! (str "api excecao: " (ex-message e)))
                  [{:teste :excecao :detalhe (ex-message e)}]))
          _ (log! (str "api ok, falhas=" (count api)))
          pureza (do (log! "rodando pureza...") (testar-pureza))
          _ (log! (str "pureza ok, violacoes=" (count pureza)))]
    (println (str "Bateria: " (- (:total bateria) (count (:falhas bateria)))
                  "/" (:total bateria) " passaram"))
    (doseq [f (:falhas bateria)]
      (println (str "  FALHA [" (:indice f) "] esperado=" (pr-str (:esperado f))
                    " obtido=" (pr-str (:obtido f)))))
    (when (seq (:sobras bateria))
      (println (str "  SOBRA de saida: " (pr-str (:sobras bateria)))))
    (println (str "API/builder: " (if (empty? api) "OK" (str (count api) " falhas"))))
    (doseq [f api]
      (println (str "  FALHA " (:teste f) " -> " (pr-str (:detalhe f)))))
    (println (str "Pureza: " (if (empty? pureza) "OK" (str (count pureza) " violacoes"))))
    (doseq [v pureza]
      (println (str "  " (:arquivo v) " " (:padrao v) " -> " (pr-str (:atingidas v)))))
    (System/exit (if (and (empty? (:falhas bateria))
                          (empty? api)
                          (empty? pureza)
                          (empty? (:sobras bateria)))
                   0
                   1)))))

(-main)
