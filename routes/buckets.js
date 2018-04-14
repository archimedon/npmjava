var express = require('express');
var router = express.Router();

// use to make GET request
const http = require("http");

/* Example of data from CloudFS */
router.get('/', function(req, response) {

    http.get("http://localhost:8080/cloudfs/api/v1/list", webResp => {
      let json = {};
      webResp.on("data", data => {
        json.bucketlist = JSON.parse(data);
      });
      webResp.on("end", () => {
        response.send(json.bucketlist.buckets);
      });
    });
});

module.exports = router;
