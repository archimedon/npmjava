#  Integrating CloudFS with Zovida

## Set up

### System Requirements

- Java 8 or higher
- Maven 3.5+
- Neo4J Should already be setup

The **LMS** app directory looks something like the tree below:

```
|-- Procfile
|-- package.json
|-- pom.xml
|-- b2intgr
|   |-- README.md
|   |-- bin
|   |-- pom.xml
|   `-- src
|-- app.js
|-- .env
|-- app.json
|-- jsdoc-conf.json
|-- node_modules
|-- scripts
|-- src
|-- test
`-- webpack.config.js
```

There are a few new files:

+ `b2intgr/`
    - Contains the BackBlaze integration
    
+ `pom.xml`
    - This is only here to let Heroku know that `maven` & `java` are required for this app
    - It is defined as the parent POM to `b2intrgr/pom.xml` and tricks Heroku into compiling the true target
    
+ `Procfile`
    - This is now **Heroku's** starting point. It starts both servers after building:
```
    web: b2intgr/bin/cloudfs.sh start && npm start
```

To run `heroku local`,  modify the `.env` file to set the following variables. Eg:
```
GRAPHENEDB_BOLT_PASSWORD=reggae
GRAPHENEDB_BOLT_USER=neo4j
GRAPHENEDB_BOLT_URL=bolt://localhost:7476
PORT=5555
```

The `PORT` value is for **expressJS**.
Both **CloudFS** and **expressJS** use the `GRAPHENE` settings.

Alternatively, one can modify the config files:

- `src/main/resources/config.json` - ([Main config](src/main/resources/config.json))
- `src/test/resources/config.json` - ([Test config](src/test/resources/config.json))

(The **test** config contains instructions and comments)


## Deploy to Heroku

Assumptions:

  1. directory initialized as git repo:
```bash
    git init`
```

  2. A Heroku app exists or was created:
```bash
    heroku create
```
----

Assuming nodejs buildpack already added:
```bash
    heroku buildpacks:add heroku/nodejs
```

- Add the Java buildpack:
```bash
    heroku buildpacks:add heroku/java
```

The java buildpack should come after `heroku/node`

- Confirm:
```bash
   heroku buildpacks
```
Output:
```
=== rdnpm Buildpack URLs
1. heroku/nodejs
2. heroku/java
```

- Add graphenedb:
```bash
    heroku addons:create graphenedb:dev-free
```

- Finally:
```bash
    git push heroku master
```

Confirm it's working:

    https://rdnpmjava.herokuapp.com/

The `/buckets` data is pulled via CloudFS

    https://rdnpmjava.herokuapp.com/buckets


## Development and Testing

Set properties in the config.json file. `$VARIABLES` may be used directly in the config file. They will be interpolated from corresponding **environment** variables. That is, for BASH:
```bash
    export VARN=value
```
The config ..:

```json

    "poolSize": 5,
    "maxPoolSize": $VARN,
    "neo4jConf": {
        "urlString": "$GRAPHENEDB_BOLT_URL",
        "username": "$GRAPHENEDB_BOLT_USER",
        "..."
    },
```

... Same as setting the values in `.env`.

### Dev cycle

During Dev it might be easier to keep CloudFS running in the background.:
```
## Start:

    b2intgr/bin/cloudfs.sh start

## Stop:

    b2intgr/bin/cloudfs.sh stop
    
## Test:

    b2intgr/bin/cloudfs.sh test
    
## Clean build

    b2intgr/bin/cloudfs.sh clean

```

Start expressJS as needed: `npm start`



