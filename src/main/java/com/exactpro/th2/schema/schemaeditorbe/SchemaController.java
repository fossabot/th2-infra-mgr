package com.exactpro.th2.schema.schemaeditorbe;

import com.exactpro.th2.schema.schemaeditorbe.errors.BadRequestException;
import com.exactpro.th2.schema.schemaeditorbe.errors.NotAcceptableException;
import com.exactpro.th2.schema.schemaeditorbe.errors.ServiceException;
import com.exactpro.th2.schema.schemaeditorbe.models.RequestEntry;
import com.exactpro.th2.schema.schemaeditorbe.models.ResourceEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Controller
public class SchemaController {

    public static final String SCHEMA_EXISTS = "SCHEMA_EXISTS";
    public static final String REPOSITORY_ERROR = "REPOSITORY_ERROR";

    @GetMapping("/schemas")
    @ResponseBody
    public Set<String> getAvailableSchemas() throws Exception  {
        return Gitter.getBranches(Config.getInstance().getGit());
    }

    @GetMapping("/schema/{name}")
    @ResponseBody
    public Set<ResourceEntry> getSchemaFiles(@PathVariable(name="name") String name) throws Exception {
        Config.GitConfig config = Config.getInstance().getGit();
        Gitter.checkout(config, name);
        return Repository.loadBranch(config, name);
    }

    @PutMapping("/schema/{name}")
    @ResponseBody
    public Set<ResourceEntry> createSchema(@PathVariable(name="name") String name) throws Exception {
        Config.GitConfig config = Config.getInstance().getGit();
        Set<String> branches = Gitter.getBranches(config);
        if (branches.contains(name))
                throw new ServiceException(HttpStatus.NOT_ACCEPTABLE, SCHEMA_EXISTS, "error crating schema. schema with this name already exists");

        Gitter.createBranch(config, name, "master");
        return Repository.loadBranch(config, name);
    }

    @PostMapping("/schema/{name}")
    @ResponseBody
    public Set<ResourceEntry> updateSchema(@PathVariable(name="name") String name, @RequestBody String requestBody) throws Exception {

        List<RequestEntry> operations = null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            operations = mapper.readValue(requestBody, new TypeReference<List<RequestEntry>>() {});
        } catch (Exception e) {
            throw new BadRequestException(e.getMessage());
        }

        Config.GitConfig config = Config.getInstance().getGit();
        Gitter.checkout(config, name);

        // apply operations
        try {
            for (RequestEntry entry : operations) {
                switch (entry.getOperation()) {
                    case add:
                        Repository.add(config, name, entry.getPayload());
                        break;
                    case update:
                        Repository.update(config, name, entry.getPayload());
                        break;
                    case remove:
                        Repository.remove(config, name, entry.getPayload());
                        break;
                }
            }
        } catch (Exception e) {
            Gitter.reset(config, name);
            throw new NotAcceptableException(REPOSITORY_ERROR, e.getMessage());
        }


        Gitter.commit(config, name, "schema update");
        return Repository.loadBranch(config, name);
    }

}