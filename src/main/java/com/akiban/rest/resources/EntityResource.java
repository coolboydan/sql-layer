/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.rest.resources;

import com.akiban.ais.AISCloner;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.protobuf.ProtobufWriter;
import com.akiban.server.entity.changes.DDLBasedSpaceModifier;
import com.akiban.server.entity.changes.SpaceDiff;
import com.akiban.server.entity.fromais.AisToSpace;
import com.akiban.server.entity.model.Space;
import com.akiban.server.entity.model.diff.JsonDiffPreview;
import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.service.security.SecurityService;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.session.SessionService;
import com.akiban.server.service.transaction.TransactionService;
import com.akiban.server.store.SchemaManager;
import com.google.inject.Inject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.security.Principal;

import static com.akiban.server.service.transaction.TransactionService.CloseableTransaction;

@Path("/entity")
public final class EntityResource {
    private static final Response FORBIDDEN = Response.status(Response.Status.FORBIDDEN).build();

    @Inject private SchemaManager schemaManager;
    @Inject private SessionService sessionService;
    @Inject private TransactionService transactionService;
    @Inject private SecurityService securityService;
    @Inject private DXLService dxlService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSpace(@Context HttpServletRequest request,
                             @QueryParam("space") String schema) {
        if(schema == null) {
            schema = getUserSchema(request);
        }
        if (schema == null || !securityService.isAccessible(request, schema)) {
            return FORBIDDEN;
        }
        try (Session session = sessionService.createSession()) {
            transactionService.beginTransaction(session);
            try {
                AkibanInformationSchema ais = schemaManager.getAis(session);
                ais = AISCloner.clone(ais, new ProtobufWriter.SingleSchemaSelector(schema));
                Space space = AisToSpace.create(ais);
                String json = space.toJson();
                return Response.status(Response.Status.OK).entity(json).build();
            }
            finally {
                transactionService.commitTransaction(session);
            }
        }
    }

    @POST
    @Path("/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response preview(@Context HttpServletRequest request,
                            final InputStream postInput) throws IOException {
        return preview(request, getUserSchema(request), postInput);
    }

    @POST
    @Path("/preview/{schema}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response preview(@Context HttpServletRequest request,
                            @PathParam("schema") String schema,
                            final InputStream postInput) throws IOException {
        return previewOrApply(request, schema, postInput, false);
    }

    @POST
    @Path("/apply")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response apply(@Context HttpServletRequest request,
                          final InputStream postInput) throws IOException {
        return apply(request, getUserSchema(request), postInput);
    }

    @POST
    @Path("/apply/{schema}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response apply(@Context HttpServletRequest request,
                          @PathParam("schema") String schema,
                          final InputStream postInput) throws IOException {
        return previewOrApply(request, schema, postInput, true);
    }

    private Response previewOrApply(HttpServletRequest request, String schema, InputStream postInput, boolean doApply) throws IOException {
        if (schema == null || !securityService.isAccessible(request, schema)) {
            return FORBIDDEN;
        }
        try (Session session = sessionService.createSession();
             CloseableTransaction txn = transactionService.beginCloseableTransaction(session)) {
            AkibanInformationSchema ais = schemaManager.getAis(session);
            ais = AISCloner.clone(ais, new ProtobufWriter.SingleSchemaSelector(schema));
            Space curSpace = AisToSpace.create(ais);
            Space newSpace = Space.create(new InputStreamReader(postInput));
            SpaceDiff diff = new SpaceDiff(curSpace, newSpace);
            if(doApply) {
                DDLBasedSpaceModifier modifier = new DDLBasedSpaceModifier(dxlService.ddlFunctions(), session, schema, newSpace);
                diff.apply(modifier);
            }
            // Successfully applied, generate output
            JsonDiffPreview preview = new JsonDiffPreview();
            diff.apply(preview);
            StringWriter json = preview.toJSON();
            if(json.getBuffer().length() == 0) {
                json.write("{}\n");
            } else {
                json.append('\n');
            }
            txn.commit();
            return Response.status(Response.Status.OK).entity(json.toString()).build();
        }
    }

    private String getUserSchema(HttpServletRequest request) {
        Principal user = request.getUserPrincipal();
        return (user == null) ? null : user.getName();
    }
}
