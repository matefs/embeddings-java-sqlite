# Embeddings Java SQLite

API REST em Java para armazenar mensagens e realizar busca semântica com embeddings locais. O projeto usa [Javalin](https://javalin.io/) para a API, [LangChain4j](https://docs.langchain4j.dev/) com o modelo All-MiniLM-L6-v2 para gerar embeddings e SQLite para persistência.

## Requisitos

- Java 17 ou mais recente
- [JBang](https://www.jbang.dev/)

## Como executar

```bash
jbang UserMessageRagApi.java
```

A aplicação inicia em `http://localhost:8080`. O arquivo `user_messages.db` é criado automaticamente no diretório atual.

## Endpoints

### Criar uma mensagem

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"userId":"usuario-1","messageText":"Estou aprendendo Java"}'
```

### Listar mensagens de um usuário

```bash
curl 'http://localhost:8080/api/messages/user/usuario-1?limit=20'
```

### Buscar mensagens semelhantes de um usuário

```bash
curl -X POST 'http://localhost:8080/api/messages/user/usuario-1/search?limit=20' \
  -H 'Content-Type: text/plain' \
  --data 'programação em Java'
```

### Buscar mensagens semelhantes de todos os usuários

```bash
curl 'http://localhost:8080/api/messages/search?query=programa%C3%A7%C3%A3o%20em%20Java&limit=20'
```

### Excluir uma mensagem

```bash
curl -X DELETE http://localhost:8080/api/messages/ID_DA_MENSAGEM
```

O parâmetro opcional `limit` aceita valores entre 1 e 100 e usa 20 por padrão.

## Como funciona

Ao receber uma mensagem, a aplicação gera um vetor de embedding e o armazena como um BLOB no SQLite. Nas buscas, ela gera o embedding do texto consultado e ordena as mensagens pela similaridade de cosseno.

Todo o processamento de embeddings acontece localmente, sem necessidade de chave de API.
